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
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Component
public class TransformEngine {

    /** 타임존 정보가 없는 시각을 해석할 기본 존. 규칙의 {@code zone} 으로 덮을 수 있다. */
    private static final String DEFAULT_ZONE = "UTC";

    // 여기는 «행마다» 불린다. 요약 INFO 를 찍으면 행 수만큼 쌓이므로 이 클래스는 DEBUG 로만
    // 흐름을 남기고, 수집/적재 건수 요약은 실행 오케스트레이터가 남긴다.
    /**
     * 원천 한 행을 표준 칸으로 옮긴다.
     *
     * @param systemValues 사람이 고르지 않고 <b>시스템이 채우는 값</b>. 어느 연동에서 왔는지
     *                     (출처), 언제 기준 자료인지(기준 시각), 언제 받아왔는지(수집 시각)는
     *                     상대가 보내주는 것이 아니라 우리가 이미 아는 값이다. 화면에서 물어보면
     *                     사장님은 넣을 칸이 없어 매핑을 끝낼 수 없다
     */
    public MappedRow map(SourceRow row, List<FieldMapping> mappings,
                         List<TargetFieldSpec> fields, Map<String, Object> systemValues) {
        log.debug("행 변환 시작 — 행={} 원천칸={}개 매핑={}개 목표필드={}개 시스템값={}개",
                row.rowNumber(), row.values().size(), mappings.size(), fields.size(),
                systemValues.size());

        Map<String, TargetFieldSpec> specByKey = new HashMap<>();
        fields.forEach(field -> specByKey.put(field.fieldKey(), field));

        Map<String, Object> values = new LinkedHashMap<>();
        // 시스템이 채우는 값을 먼저 깐다. 매핑이 같은 칸을 가리키면 사람이 정한 쪽이 이긴다 —
        // 원천이 자기 기준 시각을 실어 보내는 경우가 있고, 그때는 그것이 더 정확하다.
        values.putAll(systemValues);
        Set<String> consumed = new HashSet<>();

        for (FieldMapping mapping : mappings) {
            TargetFieldSpec spec = specByKey.get(mapping.targetFieldKey());

            // 고정값은 원천을 읽지 않는다. 칸 이름이 비어 있어 그냥 태우면 null 을 꺼내게 되고,
            // consumed 에 넣으면 «쓰지도 않은 칸» 이 보존 대상에서 빠져 사용자가 넣지 않은
            // 이유로 자료가 사라진다.
            if (spec != null && mapping.rule().type() == TransformType.CONSTANT
                    && isRealConstant(mapping)) {
                String constant = mapping.rule().param("value", "");
                Object converted = coerce(constant, spec, mapping.rule());
                values.put(mapping.targetFieldKey(), converted);
                log.debug("고정값 적용 — 행={} 목표칸={} 설정값='{}' 최종값={}({})",
                        row.rowNumber(), mapping.targetFieldKey(), constant, converted,
                        spec.dataType());
                continue;
            }

            consumed.add(mapping.sourceField());

            if (spec == null) {
                // 목표 모델에 없는 키로 매핑돼 있으면 무시한다. 모델에서 필드를 지웠는데
                // 매핑이 남아 있는 경우이며, 그 자체로 실행을 막을 이유는 없다.
                // 매핑 정의 문제라 행마다 같은 내용이 반복된다 — 첫 행만 WARN 으로 올린다.
                if (row.rowNumber() <= 1) {
                    log.warn("목표 모델에 없는 칸으로 매핑돼 있어 건너뛴다 — 행={} 원천칸={} 목표칸={}",
                            row.rowNumber(), mapping.sourceField(), mapping.targetFieldKey());
                } else {
                    log.debug("목표 모델에 없는 칸이라 건너뛴다 — 행={} 원천칸={} 목표칸={}",
                            row.rowNumber(), mapping.sourceField(), mapping.targetFieldKey());
                }
                continue;
            }

            // 원천에 칸 자체가 없는 것과 값이 빈 것은 원인이 다르다. 「왜 이 칸이 비었나」 를
            // 로그만으로 가르려면 존재 여부를 따로 남겨야 한다.
            if (!row.values().containsKey(mapping.sourceField())) {
                log.debug("원천에 없는 칸이라 빈 값으로 처리한다 — 행={} 원천칸={} 목표칸={} 원천보유칸={}",
                        row.rowNumber(), mapping.sourceField(), mapping.targetFieldKey(),
                        row.values().keySet());
            }

            String raw = Objects.toString(row.values().get(mapping.sourceField()), "");
            String applied = applyRule(raw, mapping.rule());
            Object converted = coerce(applied, spec, mapping.rule());
            values.put(mapping.targetFieldKey(), converted);
            log.debug("칸 매핑 — 행={} 원천칸={} → 목표칸={} 규칙={} 원값='{}' 규칙적용='{}' 최종값={}({})",
                    row.rowNumber(), mapping.sourceField(), mapping.targetFieldKey(),
                    mapping.rule().type(), raw, applied, converted, spec.dataType());
            if (!applied.equals(raw)) {
                log.debug("변환 규칙이 값을 바꿨다 — 행={} 목표칸={} 규칙={} 파라미터={} '{}' → '{}'",
                        row.rowNumber(), mapping.targetFieldKey(), mapping.rule().type(),
                        mapping.rule().params(), raw, applied);
            }
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        row.values().forEach((key, value) -> {
            if (!consumed.contains(key)) {
                attributes.put(key, value);
            }
        });

        log.debug("행 변환 완료 — 행={} 채운칸={}개 보존속성={}개 값={}",
                row.rowNumber(), values.size(), attributes.size(), values);

        return new MappedRow(row.rowNumber(), values, attributes);
    }

    /**
     * 「고정값」 인데 넣을 값이 없는 줄.
     *
     * <p>값 없는 고정값은 <b>고정값이 아니다.</b> 그런데도 그렇게 취급하면 원천 칸을 골라 뒀어도
     * 쳐다보지 않고 빈 값을 넣는다 — 화면에는 고른 칸이 그대로 보이므로 사람은 원인을 알 수 없다.
     *
     * <p>실제로 그 상태로 저장된 자료가 있었다. 화면을 고쳐도 이미 저장된 줄은 스스로를 계속
     * 재생산해(고정값으로 보임 → 고정값으로 저장) 빠져나올 방법이 없었다. 그래서 <b>읽는 쪽에서</b>
     * 끊는다. 고를 칸이 있으면 그것을 읽는 편이 언제나 낫다.
     */
    private boolean isRealConstant(FieldMapping mapping) {
        if (!mapping.rule().param("value", "").isBlank()) {
            return true;
        }
        if (mapping.sourceField() == null || mapping.sourceField().isBlank()) {
            // 고를 칸도 없다. 빈 값을 넣는 것 말고 할 수 있는 것이 없다.
            return true;
        }
        log.warn("값 없는 고정값이라 고른 칸을 읽는다 — 목표칸={} 원천칸={} (매핑을 다시 저장하면 정리된다)",
                mapping.targetFieldKey(), mapping.sourceField());
        return false;
    }

    /**
     * 필수 칸이 채워졌는가.
     *
     * <p><b>매핑에서 떼어내 따로 둔 이유</b> — 매핑과 담기 사이에 사장님이 쓴 후처리 스크립트가
     * 돈다. 검사를 매핑 안에서 끝내면 스크립트가 그 뒤에 필수 칸을 지워도 통과한 채로 담겨,
     * 빈 품목코드가 조용히 들어간다. 검사는 <b>담기 직전, 마지막에</b> 있어야 한다.
     *
     * <p>이 행만 버리고 나머지는 계속 담긴다. 한 줄 때문에 전체를 버리지 않는다.
     */
    public void verifyRequired(MappedRow row, List<TargetFieldSpec> fields,
                               List<FieldMapping> mappings, Map<String, Object> sourceValues) {
        for (TargetFieldSpec spec : fields) {
            if (spec.required() && !StringUtils.hasText(
                    Objects.toString(row.values().get(spec.fieldKey()), ""))) {
                // 원인은 대개 둘 중 하나다 — 매핑 자체가 없거나, 원천 값이 비었거나.
                // 어느 원천칸에서 오기로 했는지 같이 남겨야 어느 쪽인지 갈린다.
                log.warn("필수 칸이 비어 이 행을 버린다 — 행={} 필수칸={} 원천칸={} 채운칸={} 원천값={}",
                        row.rowNumber(), spec.fieldKey(),
                        sourceFieldOf(mappings, spec.fieldKey()), row.values().keySet(),
                        sourceValues);
                throw new BusinessException(ErrorCode.REQUIRED_FIELD_MISSING, spec.fieldKey());
            }
        }
    }

    /** 실패 로그용. 필수칸이 어느 원천칸에서 오기로 되어 있었는지 짚어준다. */
    private String sourceFieldOf(List<FieldMapping> mappings, String targetFieldKey) {
        return mappings.stream()
                .filter(mapping -> Objects.equals(mapping.targetFieldKey(), targetFieldKey))
                .map(FieldMapping::sourceField)
                .findFirst()
                .orElse("(매핑없음)");
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
            // 위에서 이미 처리하고 넘어오지만, 다른 경로로 들어와도 원천 값에 좌우되지 않아야 한다.
            case CONSTANT -> rule.param("value", "");
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
            log.debug("날짜 정규화 건너뜀 — 패턴='{}' 값='{}' (둘 중 하나가 비었다)", pattern, value);
            return value;
        }
        try {
            String normalized =
                    LocalDate.parse(value.trim(), DateTimeFormatter.ofPattern(pattern)).toString();
            log.debug("날짜 정규화 — 패턴={} '{}' → '{}'", pattern, value, normalized);
            return normalized;
        } catch (DateTimeParseException | IllegalArgumentException e) {
            // 여기서 멈추지 않고 원본을 넘기므로 WARN. 스택은 값·패턴 불일치라 도움이 안 돼 사유만 남긴다.
            log.warn("날짜 정규화 실패해 원본을 그대로 넘긴다 — 패턴={} 값='{}' 사유={}",
                    pattern, value, e.toString());
            return value;
        }
    }

    private Object coerce(String value, TargetFieldSpec spec, TransformRule rule) {
        if (!StringUtils.hasText(value)) {
            log.debug("값이 비어 기본값을 쓴다 — 칸={} 형식={} 기본값={}",
                    spec.fieldKey(), spec.dataType(), spec.defaultValue());
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
            log.warn("숫자로 바꿀 수 없는 값 — 칸={} 값='{}' 기대형식={}",
                    spec.fieldKey(), value, spec.dataType());
            throw new BusinessException(ErrorCode.FIELD_TYPE_MISMATCH,
                    spec.fieldKey(), value, spec.dataType().name());
        }
    }

    private boolean parseBoolean(String value, TargetFieldSpec spec) {
        return switch (value.toLowerCase()) {
            case "true", "y", "yes", "1", "예" -> true;
            case "false", "n", "no", "0", "아니오" -> false;
            default -> {
                log.warn("참/거짓으로 해석할 수 없는 값 — 칸={} 값='{}' 허용값=true/y/yes/1/예, false/n/no/0/아니오",
                        spec.fieldKey(), value);
                throw new BusinessException(ErrorCode.FIELD_TYPE_MISMATCH,
                        spec.fieldKey(), value, spec.dataType().name());
            }
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
            log.debug("ISO 날짜로 못 읽어 일시 형식으로 재시도 — 칸={} 값='{}'",
                    spec.fieldKey(), value);
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate();
        } catch (DateTimeParseException e) {
            log.warn("날짜로 바꿀 수 없는 값 — 칸={} 값='{}' 기대형식=yyyy-MM-dd 또는 ISO 일시",
                    spec.fieldKey(), value);
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
            log.debug("타임존 붙은 시각이 아니라 지역 시각으로 재시도 — 칸={} 값='{}' 존={}",
                    spec.fieldKey(), value, rule.param("zone", DEFAULT_ZONE));
        }
        try {
            ZoneId zone = ZoneId.of(rule.param("zone", DEFAULT_ZONE));
            Instant parsed = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(zone).toInstant();
            // 존 해석이 값을 바꾸므로 «왜 시각이 밀렸나» 를 추적하려면 어떤 존을 썼는지 남겨야 한다.
            log.debug("지역 일시를 시각으로 해석 — 칸={} 값='{}' 존={} → {}",
                    spec.fieldKey(), value, zone, parsed);
            return parsed;
        } catch (DateTimeException e) {
            // 날짜만 온 경우도 받는다. 기준일 필드에 흔하다.
            try {
                ZoneId zone = ZoneId.of(rule.param("zone", DEFAULT_ZONE));
                Instant parsed = LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
                        .atStartOfDay(zone).toInstant();
                log.debug("날짜만 와서 자정으로 해석 — 칸={} 값='{}' 존={} → {}",
                        spec.fieldKey(), value, zone, parsed);
                return parsed;
            } catch (DateTimeException inner) {
                log.warn("시각으로 바꿀 수 없는 값 — 칸={} 값='{}' 존={} 기대형식=ISO 시각/일시/날짜",
                        spec.fieldKey(), value, rule.param("zone", DEFAULT_ZONE));
                throw new BusinessException(ErrorCode.FIELD_TYPE_MISMATCH,
                        spec.fieldKey(), value, spec.dataType().name());
            }
        }
    }
}
