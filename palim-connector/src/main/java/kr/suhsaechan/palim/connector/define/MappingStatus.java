package kr.suhsaechan.palim.connector.define;

/**
 * 매핑 버전의 상태.
 *
 * <p>{@code DRAFT} 로도 <b>테스트 실행은 가능하다</b> — 확정 전에 결과를 눈으로 보는 것이
 * 그 목적이다. 실제 적재(LIVE)만 {@code ACTIVE} 를 요구한다. 확정 이력이 없으면 나중에
 * "어느 정의로 넣은 데이터인가"를 설명할 수 없기 때문이다.
 */
public enum MappingStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}
