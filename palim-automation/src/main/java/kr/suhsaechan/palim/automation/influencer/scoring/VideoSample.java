package kr.suhsaechan.palim.automation.influencer.scoring;

import java.time.Instant;

/** 스코어링 입력이 되는 영상 1건의 스냅샷. 수집 계층(Plan 2)이 이 형태로 변환해 넘긴다. */
public record VideoSample(
        String videoId,
        Instant publishedAt,
        int durationSeconds,
        long viewCount,
        long likeCount,
        long commentCount,
        boolean paidPromotion) {
}
