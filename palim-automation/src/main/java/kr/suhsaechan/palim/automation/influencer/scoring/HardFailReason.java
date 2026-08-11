package kr.suhsaechan.palim.automation.influencer.scoring;

/** 하드 탈락 사유. AI 는 탈락 권한이 없다 — 여기 있는 정량 조건과 수동 제외만 탈락시킨다. */
public enum HardFailReason {
    INACTIVE,
    INSUFFICIENT_VIDEOS,
    BELOW_SUBSCRIBER_MIN,
    MANUALLY_EXCLUDED
}
