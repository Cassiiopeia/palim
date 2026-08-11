package kr.suhsaechan.palim.web.influencer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerScore;
import kr.suhsaechan.palim.automation.influencer.domain.ReviewDecision;
import kr.suhsaechan.palim.automation.influencer.scoring.Badge;

/**
 * 등급표 한 줄.
 *
 * <p>사장님이 이 표에서 답을 얻어야 하는 질문은 하나다 — <b>"같은 돈으로 누가 더 많이
 * 도달하는가."</b> 그래서 구독자보다 조회수 중앙값과 CPV 를 앞에 세운다.
 *
 * @param estimatedCpv  추정 단가 ÷ 조회수 중앙값. 견적이 입력됐으면 그 값으로 계산된다
 * @param priceEstimated 추정치면 true — 화면이 "추정" 표시를 붙인다
 */
public record GradeRowView(
        UUID channelId,
        String youtubeChannelId,
        String title,
        String handle,
        long subscriberCount,
        long medianViews,
        BigDecimal ruleTotal,
        BigDecimal aiTotal,
        BigDecimal total,
        String grade,
        long price,
        boolean priceEstimated,
        BigDecimal estimatedCpv,
        List<Badge> badges,
        String hardFailReason,
        ReviewDecision decision) {

    /** 구독자 대비 조회수. 표에서 "죽은 채널"을 한눈에 가른다. */
    public BigDecimal viewToSubscriberRatio() {
        if (subscriberCount == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(medianViews)
                .divide(BigDecimal.valueOf(subscriberCount), 2, RoundingMode.HALF_UP);
    }

    public boolean hasBadge(Badge badge) {
        return badges.contains(badge);
    }

    /** AI 심층 심사 전에는 총점이 룰 점수뿐이라 화면이 "미평가"를 표시해야 한다. */
    public boolean aiReviewed() {
        return aiTotal != null;
    }

    public String gradeBadgeClass() {
        return switch (grade) {
            case "S" -> "badge-primary";
            case "A" -> "badge-success";
            case "B" -> "badge-info";
            case "C" -> "badge-warning";
            default -> "badge-ghost";
        };
    }

    static GradeRowView of(InfluencerScore score, ReviewDecision decision, long medianViews) {
        var channel = score.getChannel();
        List<Badge> badges = score.getBadges() == null || score.getBadges().isBlank()
                ? List.of()
                : java.util.Arrays.stream(score.getBadges().split(","))
                        .map(Badge::valueOf)
                        .toList();

        boolean estimated = score.getQuotedPrice() == null;
        long price = estimated ? score.getEstimatedPrice() : score.getQuotedPrice();
        BigDecimal cpv = estimated || medianViews == 0
                ? score.getEstimatedCpv()
                : BigDecimal.valueOf(price)
                        .divide(BigDecimal.valueOf(medianViews), 2, RoundingMode.HALF_UP);

        return new GradeRowView(
                channel.getId(),
                channel.getYoutubeChannelId(),
                channel.getTitle(),
                channel.getHandle(),
                channel.getSubscriberCount(),
                medianViews,
                score.getRuleTotal(),
                score.getAiTotal(),
                score.getTotal(),
                score.getGrade().name(),
                price,
                estimated,
                cpv,
                badges,
                score.getHardFailReason() == null ? null : score.getHardFailReason().name(),
                decision);
    }
}
