package kr.suhsaechan.palim.automation.influencer.scoring;

import java.util.Optional;

/** 점수 계산 전 선별. 탈락이면 점수를 계산하지 않는다(quota·AI 비용 절약). */
public final class HardFilter {

    private HardFilter() {
    }

    public static Optional<HardFailReason> check(
            ChannelMetrics m, long subscriberCount, CampaignTarget target,
            boolean manuallyExcluded, ScoringProperties props) {

        if (manuallyExcluded) {
            return Optional.of(HardFailReason.MANUALLY_EXCLUDED);
        }
        if (m.daysSinceLastUpload() > props.hardFilter().maxDaysSinceUpload()) {
            return Optional.of(HardFailReason.INACTIVE);
        }
        if (m.longformCount() < props.hardFilter().minLongformCount()) {
            return Optional.of(HardFailReason.INSUFFICIENT_VIDEOS);
        }
        if (subscriberCount < target.subscriberMin()) {
            return Optional.of(HardFailReason.BELOW_SUBSCRIBER_MIN);
        }
        return Optional.empty();
    }
}
