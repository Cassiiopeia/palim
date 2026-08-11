package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HardFilterTest {

    private static final CampaignTarget TARGET = new CampaignTarget(50_000, 300_000, 10_000, 1_000_000);

    private static ChannelMetrics metrics(int longformCount, long daysSinceLastUpload) {
        return new ChannelMetrics(longformCount, 100_000, 0.5, 0.05, 0.3,
                1.0, 1.0, 1.0, 8, daysSinceLastUpload, 0.1, 1.0, 1.0);
    }

    @Test
    void 마지막_업로드_90일_초과는_INACTIVE() {
        assertThat(HardFilter.check(metrics(20, 91), 50_000, TARGET, false, ScoringFixtures.defaultProps()))
                .contains(HardFailReason.INACTIVE);
    }

    @Test
    void 롱폼_5개_미만은_INSUFFICIENT_VIDEOS() {
        assertThat(HardFilter.check(metrics(4, 3), 50_000, TARGET, false, ScoringFixtures.defaultProps()))
                .contains(HardFailReason.INSUFFICIENT_VIDEOS);
    }

    @Test
    void 캠페인_구독자_하한_미달은_BELOW_SUBSCRIBER_MIN() {
        assertThat(HardFilter.check(metrics(20, 3), 9_999, TARGET, false, ScoringFixtures.defaultProps()))
                .contains(HardFailReason.BELOW_SUBSCRIBER_MIN);
    }

    @Test
    void 수동_제외는_MANUALLY_EXCLUDED() {
        assertThat(HardFilter.check(metrics(20, 3), 50_000, TARGET, true, ScoringFixtures.defaultProps()))
                .contains(HardFailReason.MANUALLY_EXCLUDED);
    }

    @Test
    void 전부_통과하면_empty() {
        assertThat(HardFilter.check(metrics(20, 3), 50_000, TARGET, false, ScoringFixtures.defaultProps()))
                .isEmpty();
    }
}
