package kr.suhsaechan.palim.automation.influencer.scoring;

/** 캠페인 브리프 중 스코어링에 필요한 목표 구간. 점수는 항상 캠페인 기준이다(스펙 §1). */
public record CampaignTarget(
        long targetReachMin, long targetReachMax, long subscriberMin, long subscriberMax) {
}
