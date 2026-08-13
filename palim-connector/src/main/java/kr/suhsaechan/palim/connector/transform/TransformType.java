package kr.suhsaechan.palim.connector.transform;

/**
 * 값 변환 종류.
 *
 * <p>화면에서 드롭다운으로 고른다. AI 없이도 매핑이 완성되어야 하므로, 실무에서 자주 필요한
 * 것만 유형으로 두고 나머지는 py 훅으로 넘긴다 — 유형이 늘수록 화면과 검증이 배수로 커진다.
 */
public enum TransformType {
    NONE,
    /** 앞뒤 공백 제거. */
    TRIM,
    UPPER,
    LOWER,
    /** 숫자가 아닌 문자 제거. {@code "1,200 개"} 같은 표기가 흔하다. */
    NUMBER_STRIP,
    /** 원천 날짜 형식을 ISO 로 정규화. {@code params.pattern} 필요. */
    DATE_FORMAT,
    /** 코드 치환표. {@code params} 의 키가 원천 값, 값이 바꿀 값. */
    CODE_REPLACE,
    /** 비어 있으면 {@code params.value} 로 채운다. */
    DEFAULT_IF_EMPTY,
    /**
     * 원천 칸 없이 <b>사람이 적어 넣은 값</b>을 모든 행에 넣는다. {@code params.value}.
     *
     * <p>{@link #DEFAULT_IF_EMPTY} 와 다르다 — 그것은 원천 값이 비었을 때 대신 쓰는 규칙이라
     * <b>원천에 그 칸이 있어야</b> 한다. 이것은 칸 자체가 없을 때 쓴다.
     *
     * <p>단위가 대표적이다. 단위 칸을 주지 않는 원천이 있는데, 그렇다고 단위 없이 담으면 나중에
     * BOX 와 EA 가 섞인 원천이 붙었을 때 두 수량을 구분할 수 없다. 그때는 이미 쌓인 자료를
     * 되돌릴 방법이 없다.
     */
    CONSTANT
}
