-- ============================================================
-- 인시던트 (#35) — 사람이 마감해야 하는 문제 기록
-- 오버셀 · 재고 정합성 불일치 · 미매핑을 미확인 → 확인 → 해결로 관리한다.
-- ============================================================

CREATE TABLE incident
(
    id               uuid         NOT NULL,
    type             varchar(30)  NOT NULL,
    dedupe_key       varchar(200) NOT NULL,
    title            varchar(200) NOT NULL,
    detail           text,
    status           varchar(20)  NOT NULL,
    occurrence_count integer      NOT NULL,
    last_occurred_at timestamptz  NOT NULL,
    acknowledged_at  timestamptz,
    resolved_at      timestamptz,
    resolution_note  varchar(1000),
    version          bigint,
    created_at       timestamptz,
    updated_at       timestamptz,
    CONSTRAINT pk_incident PRIMARY KEY (id)
);

-- 미해결 상태의 같은 문제는 한 행뿐이다 — 목록 도배 방지의 최종 방어선.
-- RESOLVED 이후 재발은 새 행이 된다 (해결 이력 보존).
CREATE UNIQUE INDEX ux_incident_open_dedupe ON incident (dedupe_key) WHERE status <> 'RESOLVED';

-- 기본 탭(미확인)과 상태 필터 목록이 최근 발생 순으로 읽는다.
CREATE INDEX ix_incident_status_last_occurred ON incident (status, last_occurred_at DESC);
