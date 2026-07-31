package kr.suhsaechan.palim.web.monitor;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import kr.suhsaechan.palim.channel.Channel;
import kr.suhsaechan.palim.channel.CollectStatus;
import kr.suhsaechan.palim.common.ChannelCode;

/**
 * 채널 수집 현황 표시용 (#30).
 *
 * <p>상태 판정과 표시 문자열 생성을 이 record 의 팩토리에 모은다. 템플릿에서 조건을 조합하면
 * 판정 순서가 화면 코드에 흩어져 검증할 수 없게 된다 — 판정은 여기서 끝내고 템플릿은 표시만
 * 한다. 시각은 KST 문자열로 변환한다(표시 직전 변환 규칙).
 */
public record CollectStatusView(
        ChannelCode code,
        String channelName,
        CollectHealth health,
        String lastCollectedAtText,
        String lastError,
        String collectedUntilText,
        String cursorLagText,
        int consecutiveFailureCount,
        int failureThreshold,
        String intervalText,
        String nextDueText
) {

    /**
     * 지연 판정 여유의 하한.
     *
     * <p>수집 주기가 짧은 채널이 스케줄러 틱 하나 밀린 것을 지연으로 오판하지 않도록,
     * 여유는 {@code max(주기 × 2, 5분)} 으로 계산한다.
     */
    static final Duration MIN_STALE_GRACE = Duration.ofMinutes(5);

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(DISPLAY_ZONE);

    public static CollectStatusView of(Channel channel, int failureThreshold, Instant now) {
        Instant nextDueAt = channel.getLastCollectedAt() == null
                ? null
                : channel.getLastCollectedAt().plusSeconds(channel.getCollectIntervalSeconds());

        return new CollectStatusView(
                channel.getCode(),
                channel.getCode().displayName(),
                judge(channel, failureThreshold, nextDueAt, now),
                format(channel.getLastCollectedAt()),
                channel.getLastCollectError(),
                format(channel.getCollectedUntil()),
                lagText(channel.getCollectedUntil(), now),
                channel.getConsecutiveFailureCount(),
                failureThreshold,
                durationText(Duration.ofSeconds(channel.getCollectIntervalSeconds())),
                nextDueText(nextDueAt, now, channel.isEnabled()));
    }

    private static CollectHealth judge(Channel channel, int failureThreshold,
                                       Instant nextDueAt, Instant now) {
        // 자동 중단은 비활성의 부분집합이라 먼저 검사한다. 순서를 바꾸면 "시스템이 멈춘 것"이
        // "발주자가 끈 것"으로 보인다.
        if (!channel.isEnabled()) {
            return channel.hasReachedFailureThreshold(failureThreshold)
                    ? CollectHealth.AUTO_DISABLED
                    : CollectHealth.DISABLED;
        }
        if (channel.getLastCollectStatus() == CollectStatus.FAILED) {
            return CollectHealth.FAILING;
        }
        if (channel.getLastCollectedAt() == null) {
            return CollectHealth.WAITING_FIRST;
        }
        if (isStale(channel.getCollectIntervalSeconds(), nextDueAt, now)) {
            return CollectHealth.STALE;
        }
        return CollectHealth.HEALTHY;
    }

    private static boolean isStale(int intervalSeconds, Instant nextDueAt, Instant now) {
        Duration grace = Duration.ofSeconds(intervalSeconds * 2L);
        if (grace.compareTo(MIN_STALE_GRACE) < 0) {
            grace = MIN_STALE_GRACE;
        }
        return now.isAfter(nextDueAt.plus(grace));
    }

    private static String format(Instant instant) {
        return instant == null ? "-" : FORMATTER.format(instant);
    }

    /** 커서 뒤처짐. "이 시각 이전 주문은 수집됐다"의 그 시각이 얼마나 뒤에 있는지. */
    private static String lagText(Instant collectedUntil, Instant now) {
        if (collectedUntil == null) {
            return null;
        }
        return durationText(Duration.between(collectedUntil, now)) + " 전까지 수집됨";
    }

    private static String nextDueText(Instant nextDueAt, Instant now, boolean enabled) {
        if (!enabled) {
            return "-";
        }
        if (nextDueAt == null) {
            return "즉시 (첫 수집)";
        }
        if (!now.isBefore(nextDueAt)) {
            return "지금 (예정 경과)";
        }
        return durationText(Duration.between(now, nextDueAt)) + " 후";
    }

    private static String durationText(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        if (seconds < 60) {
            return seconds + "초";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "분";
        }
        if (seconds < 86400) {
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            return minutes == 0 ? hours + "시간" : "%d시간 %d분".formatted(hours, minutes);
        }
        return (seconds / 86400) + "일";
    }
}
