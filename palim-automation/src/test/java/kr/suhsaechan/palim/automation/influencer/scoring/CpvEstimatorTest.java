package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CpvEstimatorTest {

    private static final ScoringProperties.CpvProps PROPS =
            new ScoringProperties.CpvProps(25.0, Map.of("beauty", 40.0));

    @Test
    void 기본_계수로_추정_단가와_CPV_를_계산한다() {
        // 구독 10만 × 25원 = 250만원, 조회 중앙값 5만 → CPV 50원
        CpvEstimate e = CpvEstimator.estimate(100_000, null, 50_000, PROPS);

        assertThat(e.estimatedPrice()).isEqualTo(2_500_000L);
        assertThat(e.estimatedCpv()).isCloseTo(50.0, within(1e-9));
    }

    @Test
    void 카테고리_계수가_있으면_그것을_쓴다() {
        CpvEstimate e = CpvEstimator.estimate(100_000, "beauty", 50_000, PROPS);

        assertThat(e.estimatedPrice()).isEqualTo(4_000_000L);
    }

    @Test
    void 조회수가_0이면_CPV_는_무한대_대신_0으로_반환한다() {
        CpvEstimate e = CpvEstimator.estimate(100_000, null, 0, PROPS);

        assertThat(e.estimatedCpv()).isEqualTo(0.0);
    }
}
