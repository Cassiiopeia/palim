package kr.suhsaechan.palim.web.monitor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import kr.suhsaechan.palim.incident.Incident;
import kr.suhsaechan.palim.incident.IncidentStatus;

/**
 * 인시던트 목록 표시용 (#34). 시각은 KST 문자열로 변환한다(표시 직전 변환 규칙).
 */
public record IncidentView(
        UUID id,
        String typeName,
        IncidentStatus status,
        String statusName,
        String badgeClass,
        String title,
        String detail,
        int occurrenceCount,
        String firstOccurredAtText,
        String lastOccurredAtText,
        String acknowledgedAtText,
        String resolvedAtText,
        String resolutionMemo
) {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(DISPLAY_ZONE);

    public static IncidentView from(Incident incident) {
        return new IncidentView(
                incident.getId(),
                incident.getIncidentType().displayName(),
                incident.getStatus(),
                incident.getStatus().displayName(),
                badgeClass(incident.getStatus()),
                incident.getTitle(),
                incident.getDetail(),
                incident.getOccurrenceCount(),
                format(incident.getFirstOccurredAt()),
                format(incident.getLastOccurredAt()),
                format(incident.getAcknowledgedAt()),
                format(incident.getResolvedAt()),
                incident.getResolutionMemo());
    }

    public boolean canAcknowledge() {
        return status == IncidentStatus.OPEN;
    }

    public boolean canResolve() {
        return status != IncidentStatus.RESOLVED;
    }

    private static String badgeClass(IncidentStatus status) {
        return switch (status) {
            case OPEN -> "badge-error";
            case ACKNOWLEDGED -> "badge-warning";
            case RESOLVED -> "badge-success";
        };
    }

    private static String format(Instant instant) {
        return instant == null ? null : FORMATTER.format(instant);
    }
}
