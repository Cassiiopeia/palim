package kr.suhsaechan.palim.automation.influencer.domain;

/** 채널 수집 상태. */
public enum ChannelStatus {

    /** 정상 — 갱신·채점 대상. */
    ACTIVE,

    /** 사람이 제외했다. 하드 탈락 사유가 되며 갱신도 하지 않는다(quota 절약). */
    EXCLUDED,

    /** 장기 무업로드로 자동 휴면 처리. 다시 업로드가 감지되면 ACTIVE 로 돌아온다. */
    DORMANT
}
