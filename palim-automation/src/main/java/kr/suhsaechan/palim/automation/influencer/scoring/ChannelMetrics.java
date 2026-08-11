package kr.suhsaechan.palim.automation.influencer.scoring;

/**
 * 채널 1개의 정량 지표. 전부 롱폼 기준·중앙값 집계다(스펙 §5 공통 규칙).
 *
 * <p>비율 필드(trend/peak/crash/velocity/burst)는 표본 부족 시 중립값 {@code 1.0} 이다 —
 * 0 이면 신생 채널이 부당하게 감점되고, 만점이면 부당하게 가점되기 때문이다.
 */
public record ChannelMetrics(
        int longformCount,
        double medianViews,
        double vsr,
        double engagementRate,
        double cv,
        double trendRatio,
        double peakRatio,
        double crashRatio,
        int uploads90d,
        long daysSinceLastUpload,
        double paidRatio,
        double velocityRatio,
        double burstRatio) {
}
