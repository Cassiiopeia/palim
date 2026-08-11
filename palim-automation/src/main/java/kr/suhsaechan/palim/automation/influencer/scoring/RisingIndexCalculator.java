package kr.suhsaechan.palim.automation.influencer.scoring;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 라이징 지수 100점 — "곧 뜰 채널"의 선행 신호를 잡는다.
 *
 * <p>광고 단가는 구독자를 후행하고 조회수는 선행하므로, 조회수가 먼저 터진 채널은
 * 단가가 오르기 전의 차익 구간에 있다. 이 지수는 그 구간의 폭발 조짐 자체를 점수화한다.
 */
public final class RisingIndexCalculator {

    private RisingIndexCalculator() {
    }

    public static RisingIndex calculate(
            ChannelMetrics m, long subscriberCount, ScoringProperties props) {

        var rising = props.rising();
        Map<String, Double> breakdown = new LinkedHashMap<>();

        breakdown.put("vsrHeat", PiecewiseLinear.interpolate(rising.vsrHeatCurve(), m.vsr()));
        breakdown.put("accel", PiecewiseLinear.interpolate(rising.accelCurve(), m.trendRatio()));
        breakdown.put("velocity", PiecewiseLinear.interpolate(rising.velocityCurve(), m.velocityRatio()));
        breakdown.put("burst", PiecewiseLinear.interpolate(rising.burstCurve(), m.burstRatio()));

        boolean untapped = m.paidRatio() <= rising.untappedMaxPaidRatio()
                && subscriberCount < rising.untappedMaxSubscribers();
        breakdown.put("untapped", untapped ? rising.untappedPoints() : 0.0);

        double total = breakdown.values().stream().mapToDouble(Double::doubleValue).sum();
        return new RisingIndex(total, breakdown, total >= rising.badgeThreshold());
    }
}
