package kr.suhsaechan.palim.connector.define;

/**
 * 수집 방식.
 *
 * <p>{@code INCREMENTAL} 은 <b>성공한 실행만</b> 커서를 전진시킨다. 실패했는데 커서가 넘어가면
 * 그 구간 데이터는 영원히 들어오지 않는다. 실패 시 그대로 두어 다음 실행이 같은 구간을 다시
 * 가져오게 하고, 중복은 자연키 UPSERT 로 흡수한다.
 */
public enum IncrementalMode {
    FULL,
    INCREMENTAL
}
