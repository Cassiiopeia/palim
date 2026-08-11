package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class RuleScorerTest {

    private static final CampaignTarget TARGET = new CampaignTarget(50_000, 300_000, 10_000, 1_000_000);

    @Test
    void 이상적인_채널은_만점에_수렴한다() {
        // 목표 구간 내 도달 · VSR 0.5 · 상승 추세 · ER 8% · 주1회 업로드 · 저변동
        ChannelMetrics m = new ChannelMetrics(50, 100_000, 0.5, 0.08, 0.3,
                1.5, 1.0, 1.0, 12, 3, 0.1, 1.0, 1.0);

        RuleScore score = RuleScorer.score(m, 200_000, TARGET, ScoringFixtures.defaultProps());

        assertThat(score.total()).isCloseTo(70.0, within(0.01));
        assertThat(score.badges()).isEmpty();
    }

    @Test
    void 죽은_채널은_낮은_점수를_받는다() {
        // VSR 0.05 · 하락 추세 · ER 1% · 업로드 뜸함 · 고변동
        ChannelMetrics m = new ChannelMetrics(50, 5_000, 0.05, 0.01, 2.0,
                0.6, 0.2, 0.9, 2, 45, 0.1, 1.0, 1.0);

        RuleScore score = RuleScorer.score(m, 100_000, TARGET, ScoringFixtures.defaultProps());

        assertThat(score.total()).isLessThan(20.0);
    }

    @Test
    void 급락_채널은_crash_0점과_배지를_받는다() {
        ChannelMetrics m = new ChannelMetrics(50, 100_000, 0.5, 0.08, 0.3,
                1.5, 1.0, 0.4, 12, 3, 0.1, 1.0, 1.0); // crashRatio 0.4 < 0.5

        RuleScore score = RuleScorer.score(m, 200_000, TARGET, ScoringFixtures.defaultProps());

        assertThat(score.badges()).contains(Badge.CRASH);
        assertThat(score.total()).isCloseTo(67.0, within(0.01)); // 만점 70 - crash 3
    }

    @Test
    void 목표_도달_구간_미달은_로그_감쇠된다() {
        // 목표 하한 50k 의 1/10 → reach 0점, 1/2 → 약 70% 점
        ChannelMetrics tenth = new ChannelMetrics(50, 5_000, 0.5, 0.08, 0.3,
                1.5, 1.0, 1.0, 12, 3, 0.1, 1.0, 1.0);
        ChannelMetrics half = new ChannelMetrics(50, 25_000, 0.5, 0.08, 0.3,
                1.5, 1.0, 1.0, 12, 3, 0.1, 1.0, 1.0);

        var props = ScoringFixtures.defaultProps();
        assertThat(RuleScorer.score(tenth, 200_000, TARGET, props).breakdown().get("reach"))
                .isCloseTo(0.0, within(0.01));
        assertThat(RuleScorer.score(half, 200_000, TARGET, props).breakdown().get("reach"))
                .isCloseTo(14.0 * (1 + Math.log10(0.5)), within(0.01));
    }

    @Test
    void 목표_구간_초과도_감쇠된다_상한의_2배면_reach_약70퍼센트() {
        ChannelMetrics over = new ChannelMetrics(50, 600_000, 0.5, 0.08, 0.3,
                1.5, 1.0, 1.0, 12, 3, 0.1, 1.0, 1.0); // 상한 300k 의 2배

        double reach = RuleScorer.score(over, 2_000_000, TARGET, ScoringFixtures.defaultProps())
                .breakdown().get("reach");

        assertThat(reach).isCloseTo(14.0 * (1 + Math.log10(0.5)), within(0.01));
    }

    @Test
    void breakdown_합계는_total_과_일치한다() {
        ChannelMetrics m = new ChannelMetrics(50, 80_000, 0.3, 0.04, 0.6,
                1.1, 0.7, 0.9, 8, 10, 0.2, 1.0, 1.0);

        RuleScore score = RuleScorer.score(m, 250_000, TARGET, ScoringFixtures.defaultProps());

        double sum = score.breakdown().values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(score.total()).isCloseTo(sum, within(1e-9));
    }
}
