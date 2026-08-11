package kr.suhsaechan.palim.automation.influencer.ai;

import java.util.List;

/**
 * AI 심사 입력.
 *
 * <p>인용 검증에 쓸 원문을 {@link #groundingSources()} 로 함께 낸다 — AI 에 보낸 것과 검증에
 * 쓰는 것이 같은 출처여야 검증이 성립한다.
 */
public record ReviewInput(
        String channelTitle,
        String channelDescription,
        List<String> categoryLabels,
        CampaignBrief campaign,
        List<VideoInput> videos,
        boolean commentsDisabled) {

    /** 캠페인 브리프 — 발주사 식별정보가 들어갈 수 있어 DB 에서만 온다. */
    public record CampaignBrief(String name, String productCategory, String targetAudience,
                                String sellingPoints, String exclusions) {
    }

    /**
     * @param transcript 자막. 없으면 null — 프롬프트에 "자막 없음"으로 표기되고 신뢰도가 낮아진다
     */
    public record VideoInput(String title, String publishedAt, boolean paidPromotion,
                             String transcript, List<CommentInput> latestComments,
                             List<CommentInput> topComments) {
    }

    /** 작성자 정보가 없다. 수집 단계에서 이미 버렸다. */
    public record CommentInput(String text, long likeCount) {
    }

    /** 인용 대조용 원문 전부. */
    public List<String> groundingSources() {
        List<String> sources = new java.util.ArrayList<>();
        sources.add(channelTitle);
        sources.add(channelDescription);
        for (VideoInput video : videos) {
            sources.add(video.title());
            if (video.transcript() != null) {
                sources.add(video.transcript());
            }
            video.latestComments().forEach(comment -> sources.add(comment.text()));
            video.topComments().forEach(comment -> sources.add(comment.text()));
        }
        // 메타데이터 인용을 허용하기 위한 합성 문장. AI 가 "유료 광고 포함 2/5편" 처럼
        // 인용할 수 있어야 광고 포화도를 근거로 들 수 있다.
        long paid = videos.stream().filter(VideoInput::paidPromotion).count();
        sources.add("유료 광고 포함 %d/%d편".formatted(paid, videos.size()));
        if (commentsDisabled) {
            sources.add("댓글이 차단되어 있습니다");
        }
        return sources;
    }

    public boolean hasTranscript() {
        return videos.stream().anyMatch(video -> video.transcript() != null);
    }
}
