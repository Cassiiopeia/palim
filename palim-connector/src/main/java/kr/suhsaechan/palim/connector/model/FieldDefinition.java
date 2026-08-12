package kr.suhsaechan.palim.connector.model;

/**
 * 표준 모델 필드의 선언.
 *
 * <p>순서는 목록에서의 위치로 정해진다. 번호를 손으로 매기면 중간에 필드를 끼울 때마다 뒤를
 * 전부 고쳐야 한다.
 */
public record FieldDefinition(String key, String displayName, FieldDataType dataType,
                              boolean required) {

    public static FieldDefinition required(String key, String displayName, FieldDataType type) {
        return new FieldDefinition(key, displayName, type, true);
    }

    public static FieldDefinition optional(String key, String displayName, FieldDataType type) {
        return new FieldDefinition(key, displayName, type, false);
    }
}
