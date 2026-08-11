-- ============================================================
-- 인플루언서 내부 심사 (#41)
--
-- 점수는 후보를 좁힐 뿐 결정을 대신하지 않는다. 최종 판단(제안·보류·제외)은 사람이 하고
-- 그 결과가 여기 남는다. 이 기록은 세 가지로 쓰인다 —
--   1) 같은 채널을 반복 검토하지 않게 하고
--   2) 나중에 루브릭의 정확도를 대조할 정답셋이 되며
--   3) DM 초안 생성 모듈의 입력이 된다.
-- ============================================================

CREATE TABLE channel_review
(
    id          uuid         NOT NULL,
    campaign_id uuid         NOT NULL,
    channel_id  uuid         NOT NULL,
    decision    varchar(20)  NOT NULL,
    note        varchar(1000),
    reviewer    varchar(50)  NOT NULL,
    decided_at  timestamptz  NOT NULL,
    version     bigint,
    created_at  timestamptz,
    updated_at  timestamptz,
    CONSTRAINT pk_channel_review PRIMARY KEY (id),
    CONSTRAINT fk_channel_review_campaign FOREIGN KEY (campaign_id)
        REFERENCES campaign (id) ON DELETE CASCADE,
    CONSTRAINT fk_channel_review_channel FOREIGN KEY (channel_id)
        REFERENCES influencer_channel (id) ON DELETE CASCADE
);

-- 캠페인×채널 판정은 하나다. 마음이 바뀌면 갱신이지 새 행이 아니다.
CREATE UNIQUE INDEX ux_channel_review_campaign_channel
    ON channel_review (campaign_id, channel_id);

CREATE INDEX ix_channel_review_decision ON channel_review (campaign_id, decision);
