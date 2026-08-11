package kr.suhsaechan.palim.automation.influencer.youtube;

import java.time.Instant;

/**
 * 영상 조회 결과.
 *
 * @param durationSeconds ISO-8601 기간을 초로 변환한 값. 쇼츠 판정의 근거다
 * @param paidPromotion   {@code paidProductPlacementDetails.hasPaidProductPlacement}
 * @param commentsDisabled 댓글 수가 응답에 없으면 차단된 상태다 — 브랜드 안전성 신호로 쓴다
 */
public record YoutubeVideoData(
        String videoId,
        String channelId,
        String title,
        String description,
        Instant publishedAt,
        int durationSeconds,
        long viewCount,
        long likeCount,
        long commentCount,
        boolean paidPromotion,
        boolean commentsDisabled,
        String categoryId) {
}
