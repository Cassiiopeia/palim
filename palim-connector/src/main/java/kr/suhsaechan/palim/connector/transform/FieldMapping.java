package kr.suhsaechan.palim.connector.transform;

/**
 * 원천 필드 → 목표 필드 연결.
 *
 * <p>JPA 엔티티가 아니라 값 객체다. 변환 엔진이 영속 계층을 모르게 해야 단위 테스트가 컨테이너
 * 없이 돌고, 규칙이 늘어도 엔진만 보면 된다.
 */
public record FieldMapping(String sourceField, String targetFieldKey, TransformRule rule) {

    public static FieldMapping of(String sourceField, String targetFieldKey) {
        return new FieldMapping(sourceField, targetFieldKey, TransformRule.none());
    }
}
