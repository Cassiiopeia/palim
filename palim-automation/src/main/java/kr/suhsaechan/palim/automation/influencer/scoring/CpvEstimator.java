package kr.suhsaechan.palim.automation.influencer.scoring;

/** 추정 단가 = 구독자 × 카테고리 계수(원). 추정 CPV = 추정 단가 ÷ 롱폼 조회수 중앙값. */
public final class CpvEstimator {

    private CpvEstimator() {
    }

    public static CpvEstimate estimate(
            long subscriberCount, String categoryCode, double medianViews,
            ScoringProperties.CpvProps props) {

        double coefficient = categoryCode == null
                ? props.defaultCoefficient()
                : props.categoryCoefficients().getOrDefault(categoryCode, props.defaultCoefficient());
        long price = Math.round(subscriberCount * coefficient);
        double cpv = medianViews > 0 ? price / medianViews : 0.0;
        return new CpvEstimate(price, cpv);
    }
}
