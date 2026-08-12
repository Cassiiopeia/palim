-- ============================================================
-- 라이징 신호 (#43)
--
-- 지수 자체는 #41 에서 이미 계산되어 점수 행의 jsonb 안에 들어가 있다. 별도 테이블로 빼는
-- 이유는 조회 성격이 다르기 때문이다 — 등급표는 "이 캠페인의 후보"를 보지만 레이더는
-- "캠페인과 무관하게 지금 뜨는 채널"을 본다. jsonb 안을 뒤져 정렬할 수는 없다.
--
-- 채널당 한 행이다. 라이징은 상태이지 이력이 아니며, 추이는 channel_snapshot 이 담당한다.
-- ============================================================

CREATE TABLE rising_signal
(
    id              uuid          NOT NULL,
    channel_id      uuid          NOT NULL,
    total           numeric(6, 2) NOT NULL,
    breakdown       jsonb         NOT NULL,
    -- 조회수 중앙값 ÷ 구독자 기반 기대 조회수. 사장님이 실제로 보는 숫자는 점수가 아니라 이것이다.
    arbitrage_ratio numeric(8, 3) NOT NULL,
    median_views    bigint        NOT NULL,
    -- 처음 감지된 시각. 라이징은 유통기한이 있는 정보라 "며칠째인지"가 판단에 직결된다.
    detected_at     timestamptz   NOT NULL,
    evaluated_at    timestamptz   NOT NULL,
    -- 성장이 꺾이면 false 로 내린다. 행을 지우지 않는 이유는 "한때 라이징이었다"가
    -- 나중에 캘리브레이션의 근거가 되기 때문이다.
    active          boolean       NOT NULL,
    version         bigint,
    created_at      timestamptz,
    updated_at      timestamptz,
    CONSTRAINT pk_rising_signal PRIMARY KEY (id),
    CONSTRAINT fk_rising_signal_channel FOREIGN KEY (channel_id)
        REFERENCES influencer_channel (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_rising_signal_channel ON rising_signal (channel_id);

-- 레이더 화면이 활성 신호를 지수순으로 읽는다.
CREATE INDEX ix_rising_signal_active_total ON rising_signal (total DESC) WHERE active;

-- 주간 알림이 "이번 주 신규 감지"를 읽는다.
CREATE INDEX ix_rising_signal_detected ON rising_signal (detected_at DESC) WHERE active;
