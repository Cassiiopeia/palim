-- ============================================================
-- 발굴 커서 (#41)
--
-- 발굴은 할당량 때문에 하루에 다 돌 수 없다. 어디까지 진행했는지 남겨두고 다음 실행에서
-- 이어받아야, 시드 키워드가 늘어나도 뒤쪽 키워드가 영영 순서를 못 받는 일이 없다.
-- ============================================================

CREATE TABLE discovery_cursor
(
    id           uuid        NOT NULL,
    source       varchar(30) NOT NULL,
    cursor_key   varchar(200) NOT NULL,
    -- YouTube 가 준 다음 페이지 토큰. null 이면 그 키의 순회가 끝났다는 뜻이다.
    page_token   varchar(500),
    last_run_at  timestamptz,
    -- 이 키로 발견한 신규 채널 누적 수. 성과 없는 키워드를 걷어내는 근거가 된다.
    found_count  integer     NOT NULL,
    created_at   timestamptz,
    updated_at   timestamptz,
    CONSTRAINT pk_discovery_cursor PRIMARY KEY (id)
);

CREATE UNIQUE INDEX ux_discovery_cursor_key ON discovery_cursor (source, cursor_key);

-- 배치가 "가장 오래 안 돌린 키" 부터 집는다.
CREATE INDEX ix_discovery_cursor_last_run ON discovery_cursor (source, last_run_at);
