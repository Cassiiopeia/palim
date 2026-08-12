package kr.suhsaechan.palim.connector.model;

import java.util.List;

/**
 * 표준 모델의 필드 정의 제공자.
 *
 * <p>{@code ConfigDefinitionProvider} 와 같은 방식이다. 구현체를 빈으로 등록하면 부팅 시 없는
 * 필드가 자동으로 채워지고 매핑 편집기에 나타난다 — 필드를 추가할 때 마이그레이션도 화면
 * 코드도 건드리지 않는다.
 *
 * <p>마이그레이션 SQL 에 넣지 않는 이유는 필드가 90개 가까이 되고 표시명·순서가 자주 바뀌기
 * 때문이다. 문구 하나 고치는 데 마이그레이션을 추가하게 된다.
 */
public interface StandardModelFields {

    /** {@code target_model.code} */
    String modelCode();

    List<FieldDefinition> fields();
}
