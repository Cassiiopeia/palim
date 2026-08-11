package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScoringPropertiesTest {

    @Test
    void 기본_YAML_이_바인딩되고_배점_합이_스펙과_일치한다() {
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
    }
}
