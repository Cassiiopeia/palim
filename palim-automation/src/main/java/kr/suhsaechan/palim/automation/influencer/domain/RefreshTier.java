package kr.suhsaechan.palim.automation.influencer.domain;

/**
 * 지표 갱신 주기 등급.
 *
 * <p>전 채널을 매일 갱신하면 quota 가 남아나지 않는다. 점수가 높거나 폭발 조짐이 있는 채널은
 * 자주, 나머지는 드물게 본다.
 */
public enum RefreshTier {

    /** 라이징 감지 채널 — 매일. 성장 곡선이 하루 단위로 필요하다. */
    RISING,

    /** 점수 상위군 — 주 1회. */
    HOT,

    /** 중위군 — 2주. */
    WARM,

    /** 하위군·신규 — 월 1회. */
    COLD
}
