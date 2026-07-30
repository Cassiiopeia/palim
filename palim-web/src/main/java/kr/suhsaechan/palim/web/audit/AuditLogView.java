package kr.suhsaechan.palim.web.audit;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import kr.suhsaechan.palim.audit.AuditLog;
import kr.suhsaechan.palim.audit.AuditType;

/**
 * 감사 로그 목록 표시용.
 *
 * <p>시각은 여기서 KST 문자열로 변환한다 — 표시 직전에만 변환한다는 규칙(CLAUDE.md 4)의
 * "표시 직전" 이 이 지점이다.
 */
public record AuditLogView(
        UUID id,
        String occurredAt,
        String actorId,
        String actorName,
        String clientIp,
        String typeName,
        String groupName,
        boolean authFailure,
        String summary,
        String beforeSnapshot,
        String afterSnapshot,
        String requestUri,
        String userAgent
) {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(DISPLAY_ZONE);

    public static AuditLogView from(AuditLog auditLog) {
        AuditType type = auditLog.getAuditType();
        return new AuditLogView(
                auditLog.getId(),
                format(auditLog.getOccurredAt()),
                nullToDash(auditLog.getActorId()),
                nullToDash(auditLog.getActorName()),
                nullToDash(auditLog.getClientIp()),
                type.displayName(),
                type.group().displayName(),
                type.isAuthFailure(),
                auditLog.getSummary(),
                auditLog.getBeforeSnapshot(),
                auditLog.getAfterSnapshot(),
                auditLog.getRequestUri(),
                auditLog.getUserAgent());
    }

    public boolean hasDetail() {
        return beforeSnapshot != null || afterSnapshot != null
                || requestUri != null || userAgent != null;
    }

    private static String format(Instant instant) {
        return instant == null ? "-" : FORMATTER.format(instant);
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
