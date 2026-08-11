package kr.suhsaechan.palim.automation.influencer.youtube;

import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * YouTube 의 ISO-8601 기간 문자열을 초로 변환한다.
 *
 * <p>이 값이 쇼츠 판정을 좌우한다. 파싱에 실패해 0 을 반환하면 <b>모든 영상이 쇼츠로 분류되어</b>
 * 채널 전체가 표본 부족으로 탈락한다. 그래서 실패를 조용히 넘기지 않고 -1 로 구분해 호출자가
 * 건너뛰게 한다.
 *
 * <p>라이브 스트리밍 예정 영상은 기간이 {@code P0D} 로 온다 — 실제 0초이므로 수집 대상이 아니다.
 */
public final class IsoDuration {

    private IsoDuration() {
    }

    /** @return 초. 파싱 불가면 -1 */
    public static int toSeconds(String iso8601) {
        if (iso8601 == null || iso8601.isBlank()) {
            return -1;
        }
        try {
            return (int) Duration.parse(iso8601).toSeconds();
        } catch (DateTimeParseException e) {
            return -1;
        }
    }
}
