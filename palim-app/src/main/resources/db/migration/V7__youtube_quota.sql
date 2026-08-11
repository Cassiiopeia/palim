-- ============================================================
-- YouTube API 일일 할당량 원장 (#41)
--
-- YouTube Data API 는 프로젝트당 하루 10,000 units 다. 발굴 검색 한 번이 100 units 라
-- 통제 없이 돌리면 오전에 소진되고 그날 수집이 통째로 멈춘다.
-- 소모를 기록해 예산을 넘기 전에 배치가 스스로 멈추고, 다음 날 커서부터 재개하게 한다.
-- ============================================================

CREATE TABLE youtube_quota_ledger
(
    id           uuid    NOT NULL,
    usage_date   date    NOT NULL,
    units_used   integer NOT NULL,
    -- 발굴 검색(search.list)은 호출당 100 units 로 압도적이라 별도 예산을 건다.
    search_units integer NOT NULL,
    created_at   timestamptz,
    updated_at   timestamptz,
    CONSTRAINT pk_youtube_quota_ledger PRIMARY KEY (id)
);

-- 하루 한 행. 동시 갱신은 낙관적 락 대신 유니크 제약 + 원자적 UPDATE 로 막는다.
CREATE UNIQUE INDEX ux_youtube_quota_ledger_date ON youtube_quota_ledger (usage_date);
