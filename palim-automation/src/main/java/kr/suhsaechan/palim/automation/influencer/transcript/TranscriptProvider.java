package kr.suhsaechan.palim.automation.influencer.transcript;

/**
 * 자막 수집 경계.
 *
 * <p>인터페이스로 격리하는 이유가 분명하다. 공식 API 는 남의 영상 자막을 주지 않아
 * 비공식 경로(yt-dlp)를 쓰는데, <b>이 경로는 언제든 깨진다</b>. 특히 클라우드 IP 대역은
 * 우선 차단 대상이다.
 *
 * <p>그래서 실패가 예외가 아니라 반환값이다 — 호출자가 폴백을 반드시 처리하게 강제한다.
 * 이 경계가 있으면 수집 방식이 바뀌어도 심사 로직은 그대로다.
 */
public interface TranscriptProvider {

    TranscriptResult fetch(String youtubeVideoId);
}
