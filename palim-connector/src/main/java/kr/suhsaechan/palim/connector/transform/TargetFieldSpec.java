package kr.suhsaechan.palim.connector.transform;

import kr.suhsaechan.palim.connector.model.FieldDataType;

/** 목표 필드의 검증 규격. {@code TargetField} 엔티티에서 뽑아낸 값 객체다. */
public record TargetFieldSpec(String fieldKey, FieldDataType dataType, boolean required,
                              String defaultValue) {

    public static TargetFieldSpec of(String fieldKey, FieldDataType dataType, boolean required) {
        return new TargetFieldSpec(fieldKey, dataType, required, null);
    }
}
