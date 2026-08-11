package kr.suhsaechan.palim.automation.influencer.domain;

/** 캠페인 진행 상태. */
public enum CampaignStatus {

    /** 브리프 작성 중 — 채점 대상이 아니다. */
    DRAFT,

    /** 진행 중 — 배치가 이 캠페인 기준으로 채점한다. */
    ACTIVE,

    /** 종료 — 점수 이력은 남기되 재채점하지 않는다. */
    CLOSED
}
