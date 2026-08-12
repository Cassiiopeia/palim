package kr.suhsaechan.palim.web.connector;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 매핑 편집기의 한 줄.
 *
 * <p>원천 필드 하나가 목표 필드 하나에 연결된다. 연결하지 않은 줄은 저장하지 않으며, 그 원천
 * 컬럼은 적재 시 {@code attributes} 로 보존된다.
 *
 * @param transformType 변환 종류 이름. 비어 있으면 변환 없음
 * @param param1        규칙 파라미터. 날짜 패턴·기본값·타임존 등 종류에 따라 뜻이 달라진다
 */
public record FieldMappingForm(String sourceField, String targetFieldKey, String transformType,
                               String param1, int order) {

    public boolean isConnected() {
        return StringUtils.hasText(targetFieldKey);
    }

    /**
     * JSONB 로 저장할 규칙.
     *
     * <p>파라미터 이름이 종류마다 다르다. 화면에서 한 칸으로 받고 여기서 이름을 붙인다 —
     * 종류별로 입력 칸을 나누면 화면이 복잡해지고, 대부분의 규칙은 파라미터가 없거나 하나다.
     */
    public Map<String, Object> toRule() {
        if (!StringUtils.hasText(transformType) || "NONE".equals(transformType)) {
            return Map.of();
        }
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("type", transformType);

        if (StringUtils.hasText(param1)) {
            rule.put("params", switch (transformType) {
                case "DATE_FORMAT" -> Map.of("pattern", param1);
                case "DEFAULT_IF_EMPTY" -> Map.of("value", param1);
                // CODE_REPLACE 는 "원본=바꿀값" 을 줄바꿈으로 나열한다.
                case "CODE_REPLACE" -> parsePairs(param1);
                default -> Map.of("zone", param1);
            });
        }
        return rule;
    }

    private Map<String, String> parsePairs(String text) {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                pairs.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        return pairs;
    }
}
