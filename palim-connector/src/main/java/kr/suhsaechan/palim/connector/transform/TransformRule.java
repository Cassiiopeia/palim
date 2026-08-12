package kr.suhsaechan.palim.connector.transform;

import java.util.Map;

/**
 * 선언적 변환 규칙.
 *
 * <p>이것이 <b>데이터</b>라는 점이 중요하다. 규칙을 코드로 두면 원천마다 클래스가 늘고 배포가
 * 필요해진다.
 *
 * @param type   변환 종류
 * @param params 종류별 파라미터. {@code DATE_FORMAT} 은 {@code pattern},
 *               {@code TIMESTAMP} 대상 필드는 {@code zone}(기본 UTC)을 쓴다
 */
public record TransformRule(TransformType type, Map<String, String> params) {

    public TransformRule {
        if (type == null) {
            type = TransformType.NONE;
        }
        if (params == null) {
            params = Map.of();
        }
    }

    public static TransformRule none() {
        return new TransformRule(TransformType.NONE, Map.of());
    }

    public static TransformRule of(TransformType type, Map<String, String> params) {
        return new TransformRule(type, params);
    }

    public String param(String key, String defaultValue) {
        return params.getOrDefault(key, defaultValue);
    }
}
