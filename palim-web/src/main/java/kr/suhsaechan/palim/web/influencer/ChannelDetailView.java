package kr.suhsaechan.palim.web.influencer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelReview;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannel;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerScore;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideo;
import kr.suhsaechan.palim.automation.influencer.domain.ReviewDecision;
import kr.suhsaechan.palim.automation.influencer.scoring.Badge;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 채널 상세 — "왜 이 점수인지"를 설명하는 화면의 모델.
 *
 * <p>점수만 보여주면 사람은 설득되지 않는다. 항목별로 <b>몇 점 만점에 몇 점인지</b>와 그 근거가
 * 되는 원지표를 함께 내야 판단할 수 있다.
 */
public record ChannelDetailView(
        UUID channelId,
        String youtubeChannelId,
        String title,
        String handle,
        String channelUrl,
        long subscriberCount,
        long medianViews,
        BigDecimal ruleTotal,
        BigDecimal aiTotal,
        BigDecimal total,
        String grade,
        List<ScoreItem> ruleItems,
        List<ScoreItem> risingItems,
        BigDecimal risingTotal,
        List<Badge> badges,
        long price,
        boolean priceEstimated,
        BigDecimal cpv,
        List<VideoRow> recentVideos,
        ReviewDecision decision,
        String reviewNote,
        String reviewer,
        String reviewedAtText) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Seoul"));

    /** 배점 대비 획득 점수. 화면은 이 비율로 막대를 그린다. */
    public record ScoreItem(String key, String label, double score, double max) {

        public int percent() {
            return max <= 0 ? 0 : (int) Math.round(score / max * 100);
        }
    }

    public record VideoRow(String videoId, String title, String publishedAt, long viewCount,
                           long likeCount, long commentCount, boolean paidPromotion) {

        public String url() {
            return "https://www.youtube.com/watch?v=" + videoId;
        }
    }

    /** 항목 키 → 화면 표시명과 만점. 만점은 설정에서 바뀔 수 있어 화면 표시용 기준값이다. */
    private static final Map<String, String> RULE_LABELS = Map.of(
            "reach", "실도달량",
            "vsr", "도달 효율",
            "momentum", "모멘텀",
            "engagement", "참여율",
            "activity", "활동성",
            "stability", "안정성");

    private static final Map<String, String> RISING_LABELS = Map.of(
            "vsrHeat", "VSR 과열",
            "accel", "가속도",
            "velocity", "조회 속도",
            "burst", "참여 폭발",
            "untapped", "미개척");

    static ChannelDetailView of(InfluencerChannel channel, InfluencerScore score,
                                ChannelReview review, List<InfluencerVideo> recent,
                                long medianViews) {
        JsonNode breakdown = MAPPER.readTree(score.getRuleBreakdown());
        List<ScoreItem> ruleItems = items(breakdown.path("rule"), RULE_LABELS);
        JsonNode rising = breakdown.path("rising");
        List<ScoreItem> risingItems = items(rising.path("breakdown"), RISING_LABELS);

        List<Badge> badges = score.getBadges() == null || score.getBadges().isBlank()
                ? List.of()
                : java.util.Arrays.stream(score.getBadges().split(",")).map(Badge::valueOf).toList();

        boolean estimated = score.getQuotedPrice() == null;

        return new ChannelDetailView(
                channel.getId(),
                channel.getYoutubeChannelId(),
                channel.getTitle(),
                channel.getHandle(),
                "https://www.youtube.com/channel/" + channel.getYoutubeChannelId(),
                channel.getSubscriberCount(),
                medianViews,
                score.getRuleTotal(),
                score.getAiTotal(),
                score.getTotal(),
                score.getGrade().name(),
                ruleItems,
                risingItems,
                BigDecimal.valueOf(rising.path("total").asDouble(0)),
                badges,
                estimated ? score.getEstimatedPrice() : score.getQuotedPrice(),
                estimated,
                score.getEstimatedCpv(),
                recent.stream().map(ChannelDetailView::toRow).toList(),
                review == null ? null : review.getDecision(),
                review == null ? null : review.getNote(),
                review == null ? null : review.getReviewer(),
                review == null ? null : DATE.format(review.getDecidedAt()));
    }

    private static List<ScoreItem> items(JsonNode node, Map<String, String> labels) {
        return labels.entrySet().stream()
                .filter(entry -> node.has(entry.getKey()))
                .map(entry -> {
                    double value = node.path(entry.getKey()).asDouble(0);
                    return new ScoreItem(entry.getKey(), entry.getValue(), value,
                            maxOf(entry.getKey()));
                })
                .sorted((a, b) -> Double.compare(b.max(), a.max()))
                .toList();
    }

    /**
     * 화면 막대의 기준 만점.
     *
     * <p>설정에서 배점을 바꾸면 실제 만점이 달라지므로 이 값은 <b>표시용 근사</b>다. 정확한
     * 현재 배점은 설정 화면에서 확인한다 — 상세 화면의 목적은 "어느 항목이 강하고 약한지"를
     * 한눈에 보는 것이라 상대 비교면 충분하다.
     */
    private static double maxOf(String key) {
        return switch (key) {
            case "reach", "vsr", "momentum" -> 14;
            case "engagement" -> 12;
            case "activity", "stability" -> 8;
            case "vsrHeat" -> 30;
            case "accel" -> 25;
            case "velocity" -> 20;
            case "burst" -> 15;
            case "untapped" -> 10;
            default -> 100;
        };
    }

    private static VideoRow toRow(InfluencerVideo video) {
        return new VideoRow(video.getYoutubeVideoId(), video.getTitle(),
                DATE.format(video.getPublishedAt()), video.getViewCount(), video.getLikeCount(),
                video.getCommentCount(), video.isPaidPromotion());
    }

    public boolean aiReviewed() {
        return aiTotal != null;
    }

    public boolean reviewed() {
        return decision != null;
    }

    public boolean hasBadge(Badge badge) {
        return badges.contains(badge);
    }

    /** 사람이 마지막 확인을 하는 통로. AI 는 사실을 확정하지 않는다. */
    public String webSearchUrl() {
        return "https://www.google.com/search?q="
                + java.net.URLEncoder.encode(title + " 논란",
                        java.nio.charset.StandardCharsets.UTF_8);
    }

    public String subscriberText() {
        return format(subscriberCount);
    }

    public String medianViewsText() {
        return format(medianViews);
    }

    private static String format(long value) {
        if (value >= 10_000) {
            return "%.1f만".formatted(value / 10_000.0);
        }
        return String.valueOf(value);
    }

    /** 최근 업로드 경과일 — 활동성 점수의 근거를 사람이 눈으로 확인하는 값. */
    public long daysSinceLastUpload(Instant now) {
        return recentVideos.isEmpty() ? -1 : Duration.between(
                Instant.parse(recentVideos.getFirst().publishedAt() + "T00:00:00Z"), now).toDays();
    }
}
