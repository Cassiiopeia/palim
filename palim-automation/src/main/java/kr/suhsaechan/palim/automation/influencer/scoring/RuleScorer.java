package kr.suhsaechan.palim.automation.influencer.scoring;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 룰 점수 70점. 조회수 계열(reach + vsr + momentum = 42점)이 60% — 광고 단가는 구독자를
 * 따라가지만 성과는 조회수로 나오는 괴리를 겨냥한 의도된 배분이다(스펙 §5).
 */
public final class RuleScorer {

    private RuleScorer() {
    }

    public static RuleScore score(
            ChannelMetrics m, long subscriberCount, CampaignTarget target, ScoringProperties props) {

        Map<String, Double> breakdown = new LinkedHashMap<>();
        Set<Badge> badges = EnumSet.noneOf(Badge.class);
        var rule = props.rule();

        breakdown.put("reach", reachScore(m.medianViews(), target, rule.reachPoints()));
        breakdown.put("vsr", PiecewiseLinear.interpolate(rule.vsr().curve(), m.vsr()));

        double momentum = PiecewiseLinear.interpolate(rule.momentum().trendCurve(), m.trendRatio())
                + PiecewiseLinear.interpolate(rule.momentum().peakCurve(), m.peakRatio());
        if (m.crashRatio() < rule.momentum().crashThreshold()) {
            badges.add(Badge.CRASH);
        } else {
            momentum += PiecewiseLinear.interpolate(rule.momentum().crashCurve(), m.crashRatio());
        }
        breakdown.put("momentum", momentum);

        breakdown.put("engagement",
                PiecewiseLinear.interpolate(rule.engagement().curve(), m.engagementRate()));

        double uploads = rule.activity().uploadsPoints()
                * Math.min(1.0, m.uploads90d() / (double) rule.activity().uploadsTarget());
        double recency = PiecewiseLinear.interpolate(rule.activity().recencyCurve(),
                m.daysSinceLastUpload());
        breakdown.put("activity", uploads + recency);

        breakdown.put("stability", PiecewiseLinear.interpolate(rule.stability().curve(), m.cv()));

        double total = breakdown.values().stream().mapToDouble(Double::doubleValue).sum();
        return new RuleScore(total, breakdown, badges);
    }

    /**
     * 실도달량 — 캠페인 목표 도달 구간 내 만점, 구간 밖은 로그 스케일 감쇠.
     * r = 구간 대비 비율(하한 미달 v/min, 상한 초과 max/v), score = points * max(0, 1 + log10(r)).
     * r=1 → 만점, r=0.5 → 약 70%, r=0.1 → 0.
     */
    private static double reachScore(double medianViews, CampaignTarget target, double points) {
        double r;
        if (medianViews < target.targetReachMin()) {
            r = medianViews / target.targetReachMin();
        } else if (medianViews > target.targetReachMax()) {
            r = target.targetReachMax() / medianViews;
        } else {
            r = 1.0;
        }
        return r <= 0 ? 0 : points * Math.max(0, 1 + Math.log10(r));
    }
}
