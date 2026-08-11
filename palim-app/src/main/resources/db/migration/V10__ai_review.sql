-- ============================================================
-- AI 심층 심사 입력 저장 (#41)
--
-- 자막과 댓글은 AI 30점의 유일한 근거다. 저장해 두는 이유는 세 가지 —
--   1) 인용 검증: AI 가 제시한 근거가 실제 원문에 있는지 코드가 대조한다
--   2) 재현성: 입력이 그대로면 AI 를 다시 부르지 않는다
--   3) 화면: 사람이 "원문 보기"로 직접 확인한다
-- ============================================================

CREATE TABLE video_transcript
(
    id         uuid        NOT NULL,
    video_id   uuid        NOT NULL,
    status     varchar(20) NOT NULL,
    language   varchar(10),
    content    text,
    -- 실패해도 행을 남긴다. 그래야 "자막이 없어서 신뢰도가 낮다"를 화면이 설명할 수 있고,
    -- 매번 다시 시도해 차단을 자초하지 않는다.
    fetched_at timestamptz NOT NULL,
    created_at timestamptz,
    updated_at timestamptz,
    CONSTRAINT pk_video_transcript PRIMARY KEY (id),
    CONSTRAINT fk_video_transcript_video FOREIGN KEY (video_id)
        REFERENCES influencer_video (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_video_transcript_video ON video_transcript (video_id);

-- 댓글에는 작성자 정보가 없다. 핸들·프로필은 개인 식별자이므로 수집 단계에서 버린다
-- (저장하지 않는 것으로는 부족하고, 매핑에서 버려야 AI 전송 경로로 새지 않는다).
CREATE TABLE video_comment
(
    id           uuid        NOT NULL,
    video_id     uuid        NOT NULL,
    sort_source  varchar(20) NOT NULL,
    content      text        NOT NULL,
    like_count   bigint      NOT NULL,
    published_at timestamptz NOT NULL,
    collected_at timestamptz NOT NULL,
    created_at   timestamptz,
    updated_at   timestamptz,
    CONSTRAINT pk_video_comment PRIMARY KEY (id),
    CONSTRAINT fk_video_comment_video FOREIGN KEY (video_id)
        REFERENCES influencer_video (id) ON DELETE CASCADE
);

CREATE INDEX ix_video_comment_video_sort ON video_comment (video_id, sort_source);
