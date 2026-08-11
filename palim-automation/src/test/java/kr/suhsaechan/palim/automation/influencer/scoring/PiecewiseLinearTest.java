package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class PiecewiseLinearTest {

    private static final List<List<Double>> CURVE =
            List.of(List.of(0.0, 0.0), List.of(0.08, 2.0), List.of(0.5, 14.0));

    @Test
    void 제어점_위의_값은_그대로_반환한다() {
        assertThat(PiecewiseLinear.interpolate(CURVE, 0.08)).isEqualTo(2.0);
    }

    @Test
    void 제어점_사이는_선형_보간한다() {
        // 0.08~0.5 구간의 중점 0.29 → 2.0~14.0 의 중점 8.0 (부동소수점 오차 허용)
        assertThat(PiecewiseLinear.interpolate(CURVE, 0.29)).isCloseTo(8.0, within(1e-9));
    }

    @Test
    void 범위_밖은_양_끝값으로_클램프한다() {
        assertThat(PiecewiseLinear.interpolate(CURVE, -1.0)).isEqualTo(0.0);
        assertThat(PiecewiseLinear.interpolate(CURVE, 9.9)).isEqualTo(14.0);
    }
}
