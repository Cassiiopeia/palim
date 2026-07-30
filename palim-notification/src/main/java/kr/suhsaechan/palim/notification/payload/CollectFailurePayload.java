package kr.suhsaechan.palim.notification.payload;

import java.time.Instant;

/**
 * 채널 수집 실패 알림 내용 (A-10).
 *
 * <p>수집이 중단되면 주문이 없는 것처럼 보여 문제 인지가 늦어진다. 고정 IP 미등록이나 인증
 * 만료가 흔한 원인이다.
 *
 * @param channelName              채널 표시명
 * @param consecutiveFailureCount  연속 실패 횟수
 * @param autoDisabled             임계 도달로 수집을 자동 중단했는지 여부
 * @param errorMessage             마지막 오류
 * @param attemptedAt              마지막 시도 시각
 */
public record CollectFailurePayload(
        String channelName,
        int consecutiveFailureCount,
        boolean autoDisabled,
        String errorMessage,
        Instant attemptedAt
) {
}
