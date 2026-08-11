-- ============================================================
-- 인플루언서 등급표 · 라이징 레이더 (#41)
-- 유튜브 공식 API 지표 기반 채널 발굴·평가. 설계는
-- docs/superpowers/specs/2026-08-11-influencer-grading-design.md
-- ============================================================

CREATE TABLE influencer_channel
(
    id                  uuid         NOT NULL,
    youtube_channel_id  varchar(64)  NOT NULL,
    title               varchar(200) NOT NULL,
    handle              varchar(100),
    country             varchar(2),
    uploads_playlist_id varchar(64),
    subscriber_count    bigint       NOT NULL,
    total_view_count    bigint       NOT NULL,
    video_count         integer      NOT NULL,
    discovery_source    varchar(30)  NOT NULL,
    refresh_tier        varchar(10)  NOT NULL,
    status              varchar(20)  NOT NULL,
    exclusion_note      varchar(500),
    last_refreshed_at   timestamptz,
    version             bigint,
    created_at          timestamptz,
    updated_at          timestamptz,
    CONSTRAINT pk_influencer_channel PRIMARY KEY (id)
);

-- 같은 채널을 두 경로(검색·차트·추천)로 발견해도 한 행이어야 한다.
CREATE UNIQUE INDEX ux_influencer_channel_youtube_id ON influencer_channel (youtube_channel_id);

-- 야간 배치가 "티어별로 갱신 기한이 지난 채널"을 읽는다.
CREATE INDEX ix_influencer_channel_tier_refreshed
    ON influencer_channel (refresh_tier, last_refreshed_at) WHERE status = 'ACTIVE';

CREATE TABLE influencer_video
(
    id               uuid        NOT NULL,
    channel_id       uuid        NOT NULL,
    youtube_video_id varchar(32) NOT NULL,
    title            varchar(300),
    published_at     timestamptz NOT NULL,
    duration_seconds integer     NOT NULL,
    short_form       boolean     NOT NULL,
    view_count       bigint      NOT NULL,
    like_count       bigint      NOT NULL,
    comment_count    bigint      NOT NULL,
    paid_promotion   boolean     NOT NULL,
    captured_at      timestamptz NOT NULL,
    created_at       timestamptz,
    updated_at       timestamptz,
    CONSTRAINT pk_influencer_video PRIMARY KEY (id),
    CONSTRAINT fk_influencer_video_channel FOREIGN KEY (channel_id)
        REFERENCES influencer_channel (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_influencer_video_youtube_id ON influencer_video (youtube_video_id);

-- 지표 계산이 "채널의 최근 롱폼 N개"를 읽는다.
CREATE INDEX ix_influencer_video_channel_published
    ON influencer_video (channel_id, published_at DESC);

CREATE TABLE channel_snapshot
(
    id               uuid    NOT NULL,
    channel_id       uuid    NOT NULL,
    captured_on      date    NOT NULL,
    subscriber_count bigint  NOT NULL,
    total_view_count bigint  NOT NULL,
    video_count      integer NOT NULL,
    created_at       timestamptz,
    updated_at       timestamptz,
    CONSTRAINT pk_channel_snapshot PRIMARY KEY (id),
    CONSTRAINT fk_channel_snapshot_channel FOREIGN KEY (channel_id)
        REFERENCES influencer_channel (id) ON DELETE CASCADE
);

-- 하루 한 행. 배치가 여러 번 돌아도 성장 곡선이 왜곡되지 않는다.
CREATE UNIQUE INDEX ux_channel_snapshot_channel_date ON channel_snapshot (channel_id, captured_on);

CREATE TABLE campaign
(
    id               uuid         NOT NULL,
    name             varchar(200) NOT NULL,
    product_category varchar(100),
    target_audience  varchar(500),
    selling_points   text,
    exclusions       text,
    target_reach_min bigint       NOT NULL,
    target_reach_max bigint       NOT NULL,
    subscriber_min   bigint       NOT NULL,
    subscriber_max   bigint       NOT NULL,
    status           varchar(20)  NOT NULL,
    version          bigint,
    created_at       timestamptz,
    updated_at       timestamptz,
    CONSTRAINT pk_campaign PRIMARY KEY (id)
);

CREATE TABLE channel_category
(
    id            uuid        NOT NULL,
    channel_id    uuid        NOT NULL,
    taxonomy      varchar(20) NOT NULL,
    category_code varchar(50) NOT NULL,
    confidence    numeric(4, 3),
    label_source  varchar(10) NOT NULL,
    labeled_at    timestamptz NOT NULL,
    created_at    timestamptz,
    updated_at    timestamptz,
    CONSTRAINT pk_channel_category PRIMARY KEY (id),
    CONSTRAINT fk_channel_category_channel FOREIGN KEY (channel_id)
        REFERENCES influencer_channel (id) ON DELETE CASCADE
);

-- 같은 분류체계의 같은 코드는 채널당 한 번만. 재분류는 갱신이지 추가가 아니다.
CREATE UNIQUE INDEX ux_channel_category_unique
    ON channel_category (channel_id, taxonomy, category_code);

CREATE INDEX ix_channel_category_lookup ON channel_category (taxonomy, category_code);

CREATE TABLE influencer_score
(
    id               uuid           NOT NULL,
    campaign_id      uuid           NOT NULL,
    channel_id       uuid           NOT NULL,
    rule_total       numeric(6, 2)  NOT NULL,
    ai_total         numeric(6, 2),
    total            numeric(6, 2)  NOT NULL,
    grade            varchar(1)     NOT NULL,
    rule_breakdown   jsonb          NOT NULL,
    ai_breakdown     jsonb,
    badges           varchar(100)   NOT NULL,
    hard_fail_reason varchar(30),
    estimated_price  bigint         NOT NULL,
    estimated_cpv    numeric(12, 2) NOT NULL,
    quoted_price     bigint,
    input_hash       varchar(64),
    rubric_version   varchar(20)    NOT NULL,
    scored_at        timestamptz    NOT NULL,
    version          bigint,
    created_at       timestamptz,
    updated_at       timestamptz,
    CONSTRAINT pk_influencer_score PRIMARY KEY (id),
    CONSTRAINT fk_influencer_score_campaign FOREIGN KEY (campaign_id)
        REFERENCES campaign (id) ON DELETE CASCADE,
    CONSTRAINT fk_influencer_score_channel FOREIGN KEY (channel_id)
        REFERENCES influencer_channel (id) ON DELETE CASCADE
);

-- 캠페인×채널 최신 점수 한 행. 재채점은 갱신이다.
CREATE UNIQUE INDEX ux_influencer_score_campaign_channel
    ON influencer_score (campaign_id, channel_id);

-- 등급표 기본 정렬(총점순)과 CPV 효율 정렬을 모두 받는다.
CREATE INDEX ix_influencer_score_campaign_total ON influencer_score (campaign_id, total DESC);
CREATE INDEX ix_influencer_score_campaign_cpv ON influencer_score (campaign_id, estimated_cpv);
