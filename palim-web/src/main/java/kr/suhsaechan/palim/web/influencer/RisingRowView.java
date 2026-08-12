package kr.suhsaechan.palim.web.influencer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.automation.influencer.rising.ArbitrageRatio;
import kr.suhsaechan.palim.automation.influencer.rising.RisingSignal;

/**
 * 라이징 레이더 한 줄.
 *
 * <p>등급표와 달리 <b>차익배율이 첫 숫자</b>다. 레이더에서 답해야 하는 질문은 "이 채널이
 * 얼마나 좋은가"가 아니라 <b>"지금 사면 얼마나 싸게 사는가"</b> 이기 때문이다.
 */
public record RisingRowView(
        UUID channelId,
        String youtubeChannelId,
        String title,
        String handle,
        long subscriberCount,
        long medianViews,
        long expectedViews,
        BigDecimal arbitrageRatio,
        BigDecimal risingScore,
        long daysSinceDetected,
        boolean hasCampaignScore) {

    /**
     * 감지 후 오래된 신호를 표시로 구분한다.
     *
     * <p>라이징은 유통기한이 있는 정보다 — 조회수가 먼저 터지고 구독자·단가가 따라오는 시차
     * 안에서만 값이 있다. 2주가 지났으면 이미 단가가 올랐을 가능성이 크다.
     */
    public boolean stale() {
        return daysSinceDetected >= 14;
    }

    /** 갓 잡힌 신호 — 가장 값이 크다. */
    public boolean fresh() {
        return daysSinceDetected <= 3;
    }

    public String subscriberText() {
        return compact(subscriberCount);
    }

    public String medianViewsText() {
        return compact(medianViews);
    }

    public String expectedViewsText() {
        return compact(expectedViews);
    }

    private static String compact(long value) {
        return value >= 10_000 ? "%.1f만".formatted(value / 10_000.0) : String.valueOf(value);
    }

    static RisingRowView of(RisingSignal signal, Instant now, boolean hasCampaignScore) {
        var channel = signal.getChannel();
        return new RisingRowView(
                channel.getId(),
                channel.getYoutubeChannelId(),
                channel.getTitle(),
                channel.getHandle(),
                channel.getSubscriberCount(),
                signal.getMedianViews(),
                Math.round(ArbitrageRatio.expectedViews(channel.getSubscriberCount())),
                signal.getArbitrageRatio(),
                signal.getTotal(),
                signal.daysSinceDetected(now),
                hasCampaignScore);
    }

    /** 화면이 채널 URL 을 만들 때 쓴다. */
    public String channelUrl() {
        return "https://www.youtube.com/channel/" + youtubeChannelId;
    }

    static List<RisingRowView> emptyList() {
        return List.of();
    }
}
