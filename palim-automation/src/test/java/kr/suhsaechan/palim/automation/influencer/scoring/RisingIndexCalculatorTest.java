package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class RisingIndexCalculatorTest {

    @Test
    void 폭발_직전_채널은_만점에_수렴하고_배지를_받는다() {
        // VSR 2.0(과열) · 가속 2배 · 조회속도 3배 · 참여 2배 · 광고 이력 없음 · 구독 5만
        ChannelMetrics m = new ChannelMetrics(30, 100_000, 2.0, 0.06, 0.5,
                2.0, 1.0, 1.5, 10, 2, 0.0, 3.0, 2.0);

        RisingIndex index = RisingIndexCalculator.calculate(m, 50_000, ScoringFixtures.defaultProps());

        assertThat(index.total()).isCloseTo(100.0, within(0.01));
        assertThat(index.risingBadge()).isTrue();
    }

    @Test
    void 정체_채널은_0점이고_배지가_없다() {
        // VSR 0.2 · 가속 없음 · 속도 보통 · 참여 보통
        ChannelMetrics m = new ChannelMetrics(30, 20_000, 0.2, 0.03, 0.5,
                1.0, 0.8, 1.0, 6, 5, 0.2, 1.0, 1.0);

        RisingIndex index = RisingIndexCalculator.calculate(m, 100_000, ScoringFixtures.defaultProps());

        assertThat(index.total()).isEqualTo(0.0);
        assertThat(index.risingBadge()).isFalse();
    }

    @Test
    void 구독자_10만_이상이면_미개척_점수를_받지_못한다() {
        ChannelMetrics m = new ChannelMetrics(30, 500_000, 2.0, 0.06, 0.5,
                2.0, 1.0, 1.5, 10, 2, 0.0, 3.0, 2.0);

        RisingIndex index = RisingIndexCalculator.calculate(m, 500_000, ScoringFixtures.defaultProps());

        assertThat(index.breakdown().get("untapped")).isEqualTo(0.0);
        assertThat(index.total()).isCloseTo(90.0, within(0.01));
    }

    @Test
    void 유료광고_이력이_5퍼센트_초과면_미개척_점수를_받지_못한다() {
        ChannelMetrics m = new ChannelMetrics(30, 100_000, 2.0, 0.06, 0.5,
                2.0, 1.0, 1.5, 10, 2, 0.1, 3.0, 2.0); // paidRatio 10%

        RisingIndex index = RisingIndexCalculator.calculate(m, 50_000, ScoringFixtures.defaultProps());

        assertThat(index.breakdown().get("untapped")).isEqualTo(0.0);
    }

    @Test
    void breakdown_합계는_total_과_일치한다() {
        ChannelMetrics m = new ChannelMetrics(30, 60_000, 1.2, 0.05, 0.5,
                1.5, 0.9, 1.2, 8, 4, 0.03, 2.0, 1.5);

        RisingIndex index = RisingIndexCalculator.calculate(m, 80_000, ScoringFixtures.defaultProps());

        double sum = index.breakdown().values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(index.total()).isCloseTo(sum, within(1e-9));
    }
}
