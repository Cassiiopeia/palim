package kr.suhsaechan.palim.automation.influencer.scoring;

/** 등급표에 점수와 별도로 크게 노출하는 상태 배지. */
public enum Badge {
    /** 알고리즘 이탈 의심 — 최근 5편 중앙값이 직전 대비 절반 미만. */
    CRASH,
    /** 라이징 지수가 임계 이상 — 매일 스냅샷 대상. */
    RISING,
    /** 최근 영상이 주간 뜨는 키워드와 겹침(부여 로직은 트렌드 모듈). */
    TREND
}
