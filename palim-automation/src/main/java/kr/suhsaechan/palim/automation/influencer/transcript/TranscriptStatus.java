package kr.suhsaechan.palim.automation.influencer.transcript;

/** 자막 수집 결과. */
public enum TranscriptStatus {

    /** 수집 성공. */
    OK,

    /** 자막이 없는 영상. 오류가 아니라 정상 결과다. */
    NONE,

    /**
     * 차단·실패.
     *
     * <p>비공개 전환, 지역 제한, yt-dlp 경로 차단이 모두 여기다. 구분해도 대응이 같아서
     * 묶는다 — 메타+댓글로 폴백하고 AI 신뢰도를 낮춘다. 연속 발생하면 경고 대상이다.
     */
    BLOCKED
}
