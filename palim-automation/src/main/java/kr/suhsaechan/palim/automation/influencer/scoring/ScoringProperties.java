package kr.suhsaechan.palim.automation.influencer.scoring;

import java.util.List;
import java.util.Map;

/**
 * 스코어링 임계값·배점 설정. 원본은 {@code influencer-scoring.yml} 이며, 발주사와 합의한
 * 루브릭 문서와 1:1 대응한다 — 코드가 아니라 이 설정을 조정하는 것이 캘리브레이션이다.
 *
 * <p>curve 필드는 전부 {@link PiecewiseLinear#interpolate(List, double)} 의 제어점이다.
 */
public record ScoringProperties(
        int shortsMaxSeconds,
        int windowSize,
        HardFilterProps hardFilter,
        RuleProps rule,
        RisingProps rising,
        GradeProps grade,
        CpvProps cpv) {

    public record HardFilterProps(int maxDaysSinceUpload, int minLongformCount) {
    }

    public record RuleProps(
            double reachPoints,
            CurveProps vsr,
            MomentumProps momentum,
            EngagementProps engagement,
            ActivityProps activity,
            CurveProps stability) {
    }

    public record CurveProps(List<List<Double>> curve) {
    }

    public record MomentumProps(
            List<List<Double>> trendCurve,
            List<List<Double>> peakCurve,
            double crashThreshold,
            List<List<Double>> crashCurve) {
    }

    public record EngagementProps(int commentWeight, List<List<Double>> curve) {
    }

    public record ActivityProps(double uploadsPoints, int uploadsTarget, List<List<Double>> recencyCurve) {
    }

    public record RisingProps(
            List<List<Double>> vsrHeatCurve,
            List<List<Double>> accelCurve,
            List<List<Double>> velocityCurve,
            List<List<Double>> burstCurve,
            double untappedPoints,
            double untappedMaxPaidRatio,
            long untappedMaxSubscribers,
            double badgeThreshold) {
    }

    public record GradeProps(int s, int a, int b, int c) {
    }

    /** 카테고리별 구독자당 추정 단가 계수(원). 미등록 카테고리는 defaultCoefficient. */
    public record CpvProps(double defaultCoefficient, Map<String, Double> categoryCoefficients) {
    }
}
