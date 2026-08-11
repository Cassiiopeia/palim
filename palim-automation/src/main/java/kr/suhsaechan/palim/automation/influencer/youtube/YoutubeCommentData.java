package kr.suhsaechan.palim.automation.influencer.youtube;

import java.time.Instant;

/**
 * 댓글 한 건.
 *
 * <p><b>작성자 정보가 없다.</b> 핸들·프로필·채널 ID 는 개인 식별자이므로 애초에 받아오지 않는다 —
 * 저장하지 않는 것으로는 부족하고, 매핑 단계에서 버려야 AI 전송 경로로 새어 나가지 않는다.
 */
public record YoutubeCommentData(
        String text,
        long likeCount,
        Instant publishedAt,
        CommentOrder order) {
}
