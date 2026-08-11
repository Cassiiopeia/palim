package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScoringPropertiesTest {

    @Test
    @DisplayName("기본 정의가 조립되고 배점 합이 스펙과 일치한다")
    void 기본_정의_조립() {
        ScoringProperties props = ScoringFixtures.defaultProps();

        assertThat(props.shortsMaxSeconds()).isEqualTo(60);
        assertThat(props.windowSize()).isEqualTo(50);
        // 룰 만점 합 70: reach 14 + vsr 14 + momentum(6+5+3) + engagement 12 + activity(5+3) + stability 8
        assertThat(props.rule().reachPoints()
                + props.rule().vsr().curve().getLast().get(1)
                + props.rule().momentum().trendCurve().getLast().get(1)
                + props.rule().momentum().peakCurve().getLast().get(1)
                + props.rule().momentum().crashCurve().getLast().get(1)
                + props.rule().engagement().curve().getLast().get(1)
                + props.rule().activity().uploadsPoints()
                + props.rule().activity().recencyCurve().getFirst().get(1)
                + props.rule().stability().curve().getFirst().get(1))
                .isEqualTo(70.0);
        // 라이징 만점 합 100: 30+25+20+15+10
        assertThat(props.rising().vsrHeatCurve().getLast().get(1)
                + props.rising().accelCurve().getLast().get(1)
                + props.rising().velocityCurve().getLast().get(1)
                + props.rising().burstCurve().getLast().get(1)
                + props.rising().untappedPoints())
                .isEqualTo(100.0);
        assertThat(props.grade().s()).isEqualTo(85);
        assertThat(props.hardFilter().maxDaysSinceUpload()).isEqualTo(90);
        assertThat(props.cpv().defaultCoefficient()).isEqualTo(25.0);
        assertThat(props.cpv().categoryCoefficients()).isEmpty();
    }

    @Test
    @DisplayName("설정을 바꾸면 재기동 없이 다음 채점부터 점수가 달라진다")
    void 설정_변경이_점수에_반영된다() {
        CampaignTarget target = new CampaignTarget(50_000, 300_000, 10_000, 1_000_000);
        // 참여율만 평범하고 나머지는 좋은 채널
        ChannelMetrics metrics = new ChannelMetrics(50, 100_000, 0.5, 0.03, 0.3,
                1.5, 1.0, 1.0, 12, 3, 0.1, 1.0, 1.0);

        double before = RuleScorer.score(metrics, 200_000, target,
                ScoringFixtures.defaultProps()).breakdown().get("engagement");

        // 참여율 곡선을 완만하게 바꾼다 — 3% 에서 만점이 나오도록
        ScoringProperties adjusted = ScoringPropertiesAssembler.assemble(
                ScoringFixtures.reader().with(ScoringConfigKeys.RULE_ENGAGEMENT_CURVE,
                        "[[0.0,0.0],[0.03,12.0]]"));
        double after = RuleScorer.score(metrics, 200_000, target, adjusted)
                .breakdown().get("engagement");

        assertThat(before).isCloseTo(5.0, within(0.01));
        assertThat(after).isCloseTo(12.0, within(0.01));
    }
}
