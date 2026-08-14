package kr.suhsaechan.palim.web.connector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMap;
import kr.suhsaechan.palim.connector.model.TargetField;
import kr.suhsaechan.palim.connector.source.SourceSchema;
import kr.suhsaechan.palim.connector.suggest.FieldSuggester;
import kr.suhsaechan.palim.connector.suggest.FieldSuggestion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 칸 연결 화면에 그릴 것을 조립한다.
 *
 * <p>세 갈래를 하나로 합친다 — 표준 항목 목록, 저장된 연결, 자동 추천. 컨트롤러에 두면 화면
 * 문구와 그룹 구분이 요청 처리 코드에 섞여, 화면을 손볼 때마다 컨트롤러를 건드리게 된다.
 */
@Component
@RequiredArgsConstructor
public class MappingViewAssembler {

    /** 미리보기에 보여줄 값 개수. 서너 개면 무엇인지 알아보기 충분하다. */
    private static final int PREVIEW_VALUES = 3;

    /**
     * 원천에 있을 수 없는 항목.
     *
     * <p>출처는 «어느 연동에서 왔는지» 이고 수집 시각은 «언제 받았는지» 다. 저쪽 시스템이 알
     * 리 없는 값이므로 고르게 하지 않는다. 물어볼 이유가 없는 것을 물으면 사람은 답을 찾느라
     * 화면을 떠난다.
     */
    // 시스템이 채우는 값. 상대가 보내주는 것이 아니라 우리가 이미 아는 값이라 물어보지 않는다 —
    // 물어보면 사장님은 넣을 칸이 없어 매핑을 끝낼 수 없다. 실제로 채우는 곳은 ConnectorRunner 다.
    private static final List<String> SYSTEM_FILLED =
            List.of("source", "base_at", "collected_at");

    private final FieldSuggester suggester;

    /**
     * @param targetFields 담을 표준 항목 전부
     * @param existing     이미 저장된 연결 (targetFieldKey → 연결)
     * @param schema       원천이 준 칸과 샘플. 아직 못 받았으면 {@code null}
     * @param targetModel  표준 모델 코드. 추천에 쓴다
     */
    public List<MappingGroupView> assemble(List<TargetField> targetFields,
                                           Map<String, ConnectorFieldMap> existing,
                                           SourceSchema schema, String targetModel) {
        Map<String, FieldSuggestion> suggested = suggestions(schema, targetModel);
        Map<String, List<String>> previews = previews(schema);

        Map<String, List<MappingRowView>> grouped = new LinkedHashMap<>();
        for (FieldGroup group : FieldGroup.values()) {
            grouped.put(group.title, new ArrayList<>());
        }

        for (TargetField field : targetFields) {
            MappingRowView row = toRow(field, existing, suggested, previews);
            grouped.get(FieldGroup.of(field).title).add(row);
        }

        List<MappingGroupView> views = new ArrayList<>();
        for (FieldGroup group : FieldGroup.values()) {
            List<MappingRowView> rows = grouped.get(group.title);
            if (!rows.isEmpty()) {
                views.add(new MappingGroupView(group.title, group.hint, rows));
            }
        }
        return views;
    }

    /**
     * 우리 항목에 자리가 없는 원천 칸.
     *
     * <p>버리지 않고 보관하지만, 그 사실을 <b>사람이 알아야</b> 한다. 화면이 말하지 않으면
     * 자기 자료가 어디 갔는지 모른 채 넘어간다.
     */
    public List<LeftoverView> leftovers(SourceSchema schema,
                                        Map<String, ConnectorFieldMap> existing) {
        if (schema == null) {
            return List.of();
        }
        List<String> connected = existing.values().stream()
                .map(ConnectorFieldMap::getSourceField)
                .filter(StringUtils::hasText)
                .toList();
        Map<String, List<String>> previews = previews(schema);

        return schema.fields().stream()
                .filter(field -> !connected.contains(field))
                .map(field -> new LeftoverView(field, preview(previews.get(field))))
                .toList();
    }

    /** 우리 항목에 없는 칸 하나. */
    public record LeftoverView(String sourceField, String preview) {
    }

    private MappingRowView toRow(TargetField field, Map<String, ConnectorFieldMap> existing,
                                 Map<String, FieldSuggestion> suggested,
                                 Map<String, List<String>> previews) {
        String key = field.getFieldKey();

        if (SYSTEM_FILLED.contains(key)) {
            return new MappingRowView(key, field.getDisplayName(), field.isRequired(), "AUTO",
                    null, null, autoHint(key), List.of());
        }

        ConnectorFieldMap saved = existing.get(key);
        if (saved != null) {
            String constant = constantOf(saved);
            String mode = constant != null ? "CONSTANT"
                    : StringUtils.hasText(saved.getSourceField()) ? "SELECT" : "NONE";
            return new MappingRowView(key, field.getDisplayName(), field.isRequired(), mode,
                    saved.getSourceField(), constant,
                    preview(previews.get(saved.getSourceField())), List.of());
        }

        // 저장된 것이 없으면 추천을 본다. 추천은 사람이 고치라고 «미리 골라 두는» 것이다.
        FieldSuggestion suggestion = suggested.get(key);
        if (suggestion != null) {
            return new MappingRowView(key, field.getDisplayName(), field.isRequired(), "SELECT",
                    suggestion.sourceField(), null,
                    preview(previews.get(suggestion.sourceField())), suggestion.reasons());
        }
        return new MappingRowView(key, field.getDisplayName(), field.isRequired(), "NONE",
                null, null, null, List.of());
    }

    private Map<String, FieldSuggestion> suggestions(SourceSchema schema, String targetModel) {
        if (schema == null || schema.fields().isEmpty()) {
            return Map.of();
        }
        Map<String, FieldSuggestion> byTarget = new LinkedHashMap<>();
        suggester.suggest(schema.fields(), schema.sampleRows(), targetModel)
                .forEach(suggestion -> byTarget.put(suggestion.targetFieldKey(), suggestion));
        return byTarget;
    }

    private Map<String, List<String>> previews(SourceSchema schema) {
        if (schema == null) {
            return Map.of();
        }
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (String field : schema.fields()) {
            values.put(field, schema.sampleRows().stream()
                    .map(row -> row.get(field))
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .limit(PREVIEW_VALUES)
                    .toList());
        }
        return values;
    }

    /**
     * 값 미리보기 문자열.
     *
     * <p>소수점이 길게 붙은 숫자를 그대로 두면 읽을 수 없다. 원천이 {@code 9451.0000000000}
     * 으로 주는 일이 흔한데, 사람이 보려는 것은 {@code 9,451} 이다.
     */
    private static String preview(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(" · ", values.stream().map(MappingViewAssembler::readable).toList());
    }

    private static String readable(String value) {
        try {
            java.math.BigDecimal number = new java.math.BigDecimal(value.replace(",", "").trim());
            return new java.text.DecimalFormat("#,##0.###").format(number);
        } catch (NumberFormatException e) {
            return value.length() <= 24 ? value : value.substring(0, 24) + "…";
        }
    }

    private static String autoHint(String fieldKey) {
        return switch (fieldKey) {
            case "source" -> "이 연동 이름이 들어갑니다";
            case "base_at" -> "재고를 물어본 날짜가 들어갑니다";
            case "collected_at" -> "받아온 시각이 들어갑니다";
            default -> "시스템이 채웁니다";
        };
    }

    /** 고정값으로 채운 줄인가. 규칙에서 값을 꺼낸다. */
    private static String constantOf(ConnectorFieldMap map) {
        Map<String, Object> rule = map.getTransformRule();
        if (rule == null || !"CONSTANT".equals(String.valueOf(rule.get("type")))) {
            return null;
        }
        Object params = rule.get("params");
        if (params instanceof Map<?, ?> values) {
            return Objects.toString(values.get("value"), "");
        }
        return "";
    }

    /**
     * 항목 묶음.
     *
     * <p>모델 정의가 아니라 화면 사정이므로 여기 둔다. 표준 모델에 넣으면 화면 구성을 바꿀 때마다
     * 도메인을 건드리게 되고, 모델마다 묶음이 다를 수 있다.
     */
    private enum FieldGroup {
        REQUIRED("꼭 필요한 항목", "비면 저장되지 않습니다"),
        LOCATION("창고·위치", null),
        QUANTITY("수량 상세", null),
        LOT("로트·기한", null),
        NAMING("이름", null),
        MONEY("금액·상태", null),
        OTHER("그 밖에", null);

        private final String title;
        private final String hint;

        FieldGroup(String title, String hint) {
            this.title = title;
            this.hint = hint;
        }

        static FieldGroup of(TargetField field) {
            String key = field.getFieldKey();
            if (field.isRequired() || SYSTEM_FILLED.contains(key)) {
                return REQUIRED;
            }
            if (key.startsWith("warehouse") || key.startsWith("location")
                    || key.startsWith("zone")) {
                return LOCATION;
            }
            if (key.startsWith("lot") || key.contains("expiry") || key.contains("manufacture")
                    || key.startsWith("serial")) {
                return LOT;
            }
            if (key.contains("quantity") || key.contains("unit")) {
                return QUANTITY;
            }
            if (key.contains("name") || key.contains("key")) {
                return NAMING;
            }
            if (key.contains("cost") || key.contains("amount") || key.contains("currency")
                    || key.contains("status")) {
                return MONEY;
            }
            return OTHER;
        }
    }
}
