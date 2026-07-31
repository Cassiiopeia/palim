package kr.suhsaechan.palim.web.monitor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import kr.suhsaechan.palim.notification.NotificationOutbox;
import kr.suhsaechan.palim.notification.OutboxStatus;

/**
 * 알림 발송 이력 표시용 (#32).
 *
 * <p>시각은 여기서 KST 문자열로 변환한다(표시 직전 변환 규칙). payload 는 원문 그대로 담고
 * 템플릿이 {@code th:text} 로만 출력한다 — 저장형 XSS 방어의 마지막 단이다.
 */
public record NotificationHistoryView(
        UUID id,
        String createdAtText,
        String typeName,
        boolean urgent,
        OutboxStatus status,
        String statusName,
        String badgeClass,
        int attemptCount,
        String sentAtText,
        String lastError,
        String payload,
        boolean retryable
) {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(DISPLAY_ZONE);

    public static NotificationHistoryView from(NotificationOutbox outbox) {
        return new NotificationHistoryView(
                outbox.getId(),
                format(outbox.getCreatedAt()),
                outbox.getType().displayName(),
                outbox.getType().isUrgent(),
                outbox.getStatus(),
                statusName(outbox.getStatus()),
                badgeClass(outbox.getStatus()),
                outbox.getAttemptCount(),
                format(outbox.getSentAt()),
                outbox.getLastError(),
                outbox.getPayload(),
                outbox.isFailed());
    }

    private static String statusName(OutboxStatus status) {
        return switch (status) {
            case PENDING -> "대기";
            case SENT -> "발송됨";
            case FAILED -> "실패";
        };
    }

    private static String badgeClass(OutboxStatus status) {
        return switch (status) {
            case PENDING -> "badge-info";
            case SENT -> "badge-success";
            case FAILED -> "badge-error";
        };
    }

    private static String format(Instant instant) {
        return instant == null ? "-" : FORMATTER.format(instant);
    }
}
