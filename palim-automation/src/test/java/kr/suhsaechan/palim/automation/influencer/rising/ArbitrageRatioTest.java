package kr.suhsaechan.palim.automation.influencer.rising;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArbitrageRatioTest {

    @Test
    @DisplayName("규모에 맞는 조회수면 배율이 1 부근이다")
    void 평범한_성과() {
        long subscribers = 100_000;
        double expected = ArbitrageRatio.expectedViews(subscribers);

        assertThat(ArbitrageRatio.of(subscribers, expected)).isEqualTo(1.0);
        // 구독 10만의 기대 조회수는 국내 평균대(VSR 0.15~0.30) 안에 들어야 한다
        assertThat(expected / subscribers).isBetween(0.15, 0.30);
    }

    @Test
    @DisplayName("기대치의 3배를 내면 배율이 3이다 — 같은 돈으로 3배 도달한다는 뜻")
    void 과열_채널() {
        long subscribers = 50_000;
        double expected = ArbitrageRatio.expectedViews(subscribers);

        assertThat(ArbitrageRatio.of(subscribers, expected * 3)).isEqualTo(3.0);
    }

    @Test
    @DisplayName("대형 채널이 규모 때문에 자동으로 불리해지지 않는다")
    void 규모_중립성() {
        // 구독 2만 채널이 VSR 0.25, 구독 200만 채널이 VSR 0.15 인 상황.
        // 단순 VSR 비교면 소형이 우세해 보이지만, 그 규모에서 0.25 는 흔하고
        // 200만에서 0.15 는 어렵다. 배율은 후자를 더 높게 본다.
        double small = ArbitrageRatio.of(20_000, 20_000 * 0.25);
        double large = ArbitrageRatio.of(2_000_000, 2_000_000 * 0.15);

        assertThat(small).isLessThan(1.0);
        assertThat(large).isGreaterThan(1.0);
    }

    @Test
    @DisplayName("구독자가 10배면 기대 조회수는 약 7배로 완만하게 오른다")
    void 규모_감쇠() {
        double ratio = ArbitrageRatio.expectedViews(1_000_000)
                / ArbitrageRatio.expectedViews(100_000);

        assertThat(ratio).isBetween(6.5, 7.5);
    }

    @Test
    @DisplayName("구독자를 숨긴 채널은 배율을 계산할 수 없다")
    void 구독자_없음() {
        assertThat(ArbitrageRatio.of(0, 100_000)).isZero();
        assertThat(ArbitrageRatio.expectedViews(0)).isZero();
    }
}
