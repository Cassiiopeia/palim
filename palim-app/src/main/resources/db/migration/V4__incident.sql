-- ============================================================
-- 인시던트 (#34)
-- ============================================================
--
-- 발주자 조치가 필요한 사건의 처리 상태를 추적한다. 알림은 흘러가면 끝이지만 인시던트는
-- 미확인 → 확인 → 해결 상태를 갖는다.
--
-- dedupe_key 에 유니크 제약을 걸지 않는다. 해결된 같은 키의 행이 여럿 존재하는 것이
-- 정상이다(재발 이력). 미해결 중복 방지는 서비스 계층 조회로 처리한다.

CREATE TABLE incident
(
    id                uuid         NOT NULL,
    incident_type     varchar(30)  NOT NULL,
    status            varchar(20)  NOT NULL,
    title             varchar(300) NOT NULL,
    detail            text,
    dedupe_key        varchar(200) NOT NULL,
    occurrence_count  int          NOT NULL,
    first_occurred_at timestamptz  NOT NULL,
    last_occurred_at  timestamptz  NOT NULL,
    acknowledged_at   timestamptz,
    resolved_at       timestamptz,
    resolution_memo   varchar(1000),
    version           bigint,
    created_at        timestamptz,
    updated_at        timestamptz,
    CONSTRAINT pk_incident PRIMARY KEY (id)
);

COMMENT ON TABLE incident IS '발주자 조치가 필요한 사건. 미확인→확인→해결 추적';
COMMENT ON COLUMN incident.dedupe_key IS '{유형}:{대상}. 미해결 재발 누적 기준 — 유니크 아님(해결 이력 보존)';
COMMENT ON COLUMN incident.resolution_memo IS '해결 시 필수. 무엇을 했는지 없는 해결은 추적이 아니다';

-- 재발 누적 조회 — dedupe_key + 미해결. 미해결 행은 소수라 부분 인덱스로 작게 유지한다.
CREATE INDEX ix_incident_unresolved_key ON incident (dedupe_key) WHERE status <> 'RESOLVED';

-- 목록 화면 — 상태 탭 + 최근 발생순.
CREATE INDEX ix_incident_status_occurred ON incident (status, last_occurred_at DESC);
