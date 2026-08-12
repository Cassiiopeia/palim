package kr.suhsaechan.palim.connector.transform;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.source.SourceRow;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 매핑 정의대로 원천 행을 목표 형태로 옮긴다.
 *
 * <p>영속 계층을 모른다. 값 객체만 받아 값 객체를 돌려주므로 단위 테스트가 컨테이너 없이 돌고,
 * 규칙이 늘어도 이 클래스만 보면 된다.
 *
 * <p>실패는 <b>행 단위</b>다. 여기서 던진 예외를 실행 오케스트레이터가 잡아 그 행만
 * {@code connector_run_error} 로 보내고 나머지는 계속 적재한다.
 */
@Component
public class TransformEngine {

    /** 타임존 정보가 없는 시각을 해석할 기본 존. 규칙의 {@code zone} 으로 덮을 수 있다. */
    private static final String DEFAULT_ZONE = "UTC";

    public MappedRow map(SourceRow row, List<FieldMapping> mappings,
                         List<TargetFieldSpec> fields) {
        Map<String, TargetFieldSpec> specByKey = new HashMap<>();
        fields.forEach(field -> specByKey.put(field.fieldKey(), field));

        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> consumed = new HashSet<>();

        for (FieldMapping mapping : mappings) {
            consumed.add(mapping.sourceField());

            TargetFieldSpec spec = specByKey.get(mapping.targetFieldKey());
            if (spec == null) {
                // 목표 모델에 없는 키로 매핑돼 있으면 무시한다. 모델에서 필드를 지웠는데
                // 매핑이 남아 있는 경우이며, 그 자체로 실행을 막을 이유는 없다.
                continue;
            }

            String raw = Objects.toString(row.values().get(mapping.sourceField()), "");
            String applied = applyRule(raw, mapping.rule());
            values.put(mapping.targetFieldKey(), coerce(applied, spec, mapping.rule()));
        }

        // 필수 검사는 매핑을 다 돌린 뒤에 한다. 매핑 순서에 결과가 좌우되면 안 된다.
        for (TargetFieldSpec spec : fields) {
            if (spec.required() && !StringUtils.hasText(
                    Objects.toString(values.get(spec.fieldKey()), ""))) {
                throw new BusinessException(ErrorCode.REQUIRED_FIELD_MISSING, spec.fieldKey());
            }
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        row.values().forEach((key, value) -> {
            if (!consumed.contains(key)) {
                attributes.put(key, value);
            }
        });

        return new MappedRow(row.rowNumber(), values, attributes);
    }

    private String applyRule(String value, TransformRule rule) {
        return switch (rule.type()) {
            case NONE -> value;
            case TRIM -> value.trim();
            case UPPER -> value.toUpperCase();
            case LOWER -> value.toLowerCase();
            case NUMBER_STRIP -> value.replaceAll("[^0-9.\\-]", "");
            case CODE_REPLACE -> rule.params().getOrDefault(value, value);
            case DEFAULT_IF_EMPTY ->
                    StringUtils.hasText(value) ? value : rule.param("value", "");
            case DATE_FORMAT -> normalizeDate(value, rule);
        };
    }

    /**
     * 원천 날짜 형식을 ISO 로 정규화.
     *
     * <p>{@code 20260812} 처럼 구분자가 없는 표기가 흔하다. 패턴이 없거나 파싱에 실패하면
     * 원본을 그대로 넘겨 {@link #coerce} 가 판단하게 둔다 — 여기서 던지면 어느 단계에서
     * 실패했는지 알기 어렵다.
     */
    private String normalizeDate(String value, TransformRule rule) {
        String pattern = rule.param("pattern", "");
        if (!StringUtils.hasText(pattern) || !StringUtils.hasText(value)) {
            return value;
        }
        try {
            return LocalDate.parse(value.trim(), DateTimeFormatter.ofPattern(pattern)).toString();
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return value;
        }
    }

    private Object coerce(String value, TargetFieldSpec spec, TransformRule rule) {
        if (!StringUtils.hasText(value)) {
            return spec.defaultValue();
        }
        String trimmed = value.trim();
        try {
            return switch (spec.dataType()) {
                case STRING -> trimmed;
                case INTEGER -> Long.valueOf(trimmed);
                case DECIMAL -> new BigDecimal(trimmed);
                case BOOLEAN -> parseBoolean(trimmed, spec);
                case DATE -> parseDate(trimmed, spec);
                case TIMESTAMP -> parseTimestamp(trimmed, spec, rule);
            };
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.FIELD_TYPE_MISMATCH,
                    spec.fieldKey(), value, spec.dataType().name());
        }
    }

    private boolean parseBoolean(String value, TargetFieldSpec spec) {
        return switch (value.toLowerCase()) {
            case "true", "y", "yes", "1", "예" -> true;
            case "false", "n", "no", "0", "아니오" -> false;
            default -> throw new BusinessException(ErrorCode.FIELD_TYPE_MISMATCH,
                    spec.fieldKey(), value, spec.dataType().name());
        };
    }

    /**
     * 날짜 파싱.
     *
     * <p>엑셀에서 온 날짜는 <b>시간이 붙어서 온다</b>({@code 2027-01-01T00:00:00}). openpyxl 이
     * date 셀을 datetime 으로 읽기 때문이다. 날짜만 받는다고 가정하면 유통기한 필드가 통째로
     * 실패하므로 두 형식을 모두 받는다.
     */
    private LocalDate parseDate(String value, TargetFieldSpec spec) {
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            // 아래에서 일시 형식으로 재시도한다
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate();
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.FIELD_TYPE_MISMATCH,
                    spec.fieldKey(), value, spec.dataType().name());
        }
    }

    /**
     * 시각 파싱.
     *
     * <p>타임존이 붙은 값은 그대로 {@link Instant} 로 읽는다. 타임존이 없으면 규칙의
     * {@code zone}(기본 UTC)으로 해석한다 — 원천이 어느 지역 시각을 주는지는 원천마다 다르고,
     * 임의로 서버 시간대를 가정하면 배포 환경이 바뀔 때 값이 조용히 달라진다.
     */
    private Instant parseTimestamp(String value, TargetFieldSpec spec, TransformRule rule) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // 타임존 없는 형식으로 재시도한다
        }
        try {
            ZoneId zone = ZoneId.of(rule.param("zone", DEFAULT_ZONE));
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(zone).toInstant();
        } catch (DateTimeException e) {
            // 날짜만 온 경우도 받는다. 기준일 필드에 흔하다.
            try {
                ZoneId zone = ZoneId.of(rule.param("zone", DEFAULT_ZONE));
                return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
                        .atStartOfDay(zone).toInstant();
            } catch (DateTimeException inner) {
                throw new BusinessException(ErrorCode.FIELD_TYPE_MISMATCH,
                        spec.fieldKey(), value, spec.dataType().name());
            }
        }
    }
}
