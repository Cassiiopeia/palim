package kr.suhsaechan.palim.automation.influencer.rising;

/**
 * 차익배율 — "규모에 비해 몇 배로 도는가".
 *
 * <p>라이징 지수(100점)는 여러 신호의 합이라 <b>얼마나 이득인지</b>를 직관적으로 말해주지 않는다.
 * 사장님이 실제로 보고 판단하는 숫자는 이것이다: 이 채널이 자기 구독자 규모의 채널이 보통 내는
 * 조회수보다 몇 배를 내고 있는가.
 *
 * <p>광고 단가는 구독자 수로 매겨지므로, 이 값이 3.0 이면 <b>같은 돈으로 3배 도달</b>한다는 뜻이다.
 *
 * <h2>기대 조회수를 왜 단순 비율로 잡지 않는가</h2>
 *
 * <p>구독자 대비 조회수 비율(VSR)은 채널이 클수록 자연히 낮아진다. 구독자 200만 채널의 20%와
 * 2만 채널의 20%는 같은 성취가 아니다. 그래서 기대 조회수를 {@code 구독자^지수} 형태로 잡고
 * 지수를 1보다 작게 둔다 — 규모가 커질수록 기대치가 완만하게 오르므로 대형 채널이 자동으로
 * 불리해지지 않는다.
 */
public final class ArbitrageRatio {

    /**
     * 규모 감쇠 지수.
     *
     * <p>0.85 는 "구독자가 10배면 기대 조회수는 약 7배"에 해당한다. 국내 유튜브에서 대형 채널의
     * 조회수가 구독자에 정비례하지 않고 완만하게 붙는 관찰을 반영한 값이며, 설정으로 뺄 만큼
     * 자주 조정할 값은 아니다.
     */
    private static final double SCALE_EXPONENT = 0.85;

    /**
     * 기준 계수.
     *
     * <p>{@code 구독자^0.85 × 0.55} 가 "그 규모에서 평범한 조회수"가 되도록 맞춘 값이다.
     * 구독자 10만이면 기대 조회수 약 1.9만(VSR 0.19)으로, 국내 평균대(0.15~0.30)의 중간이다.
     */
    private static final double BASE_COEFFICIENT = 0.55;

    private ArbitrageRatio() {
    }

    /**
     * @return 기대 조회수 대비 실제 조회수 배율. 1.0 이면 규모에 맞는 평범한 성과
     */
    public static double of(long subscriberCount, double medianViews) {
        double expected = expectedViews(subscriberCount);
        if (expected <= 0) {
            return 0;
        }
        return medianViews / expected;
    }

    /** 그 구독자 규모에서 보통 나오는 조회수. */
    public static double expectedViews(long subscriberCount) {
        if (subscriberCount <= 0) {
            return 0;
        }
        return Math.pow(subscriberCount, SCALE_EXPONENT) * BASE_COEFFICIENT;
    }
}
