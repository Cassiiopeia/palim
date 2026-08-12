package kr.suhsaechan.palim.connector.model;

/**
 * 목표 필드의 값 타입.
 *
 * <p>배열·중첩 객체는 두지 않는다. 타입을 넓히면 검증·변환·화면 입력기가 전부 배수로 늘어나는데,
 * 그런 필드가 필요한 사례가 아직 없다. 필요하면 {@code attributes} 에 JSON 으로 넣는다.
 */
public enum FieldDataType {
    STRING,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    DATE,
    TIMESTAMP
}
