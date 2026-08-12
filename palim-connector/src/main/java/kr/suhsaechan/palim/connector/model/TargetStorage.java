package kr.suhsaechan.palim.connector.model;

/**
 * 적재 대상.
 *
 * <p>파이프라인(읽기·매핑·변환·검증·실행 이력)은 이 값을 모른다. <b>저장 직전에만</b> 갈라진다.
 * 그래서 커스텀 모델도 기본 제공 모델과 완전히 같은 경로를 탄다.
 */
public enum TargetStorage {
    /** 정식 테이블. 컬럼·인덱스·제약이 있고 평범한 SQL 로 조회된다. */
    TABLE,
    /** {@code custom_record.payload} JSONB. 런타임 DDL 없이 모델을 추가할 수 있다. */
    JSONB
}
