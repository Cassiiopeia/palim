package kr.suhsaechan.palim.web.monitor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import kr.suhsaechan.palim.incident.Incident;
import kr.suhsaechan.palim.incident.IncidentStatus;

/**
 * 인시던트 표시용 (#35).
 *
 * <p>시각은 여기서 KST 문자열로 변환한다(표시 직전 변환 규칙). detail·해결 메모는 원문
 * 그대로 담고 템플릿이 {@code th:text} 로만 출력한다 — 채널 상품명이 들어오므로 저장형
 * XSS 방어의 마지막 단이다.
 */
public record IncidentView(
        UUID id,
        String typeName,
        String title,
        String detail,
        IncidentStatus status,
        String statusName,
        String badgeClass,
        int occurrenceCount,
        String firstOccurredAtText,
        String lastOccurredAtText,
        String resolvedAtText,
        String resolutionNote,
        boolean acknowledgeable,
        boolean resolvable
) {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(DISPLAY_ZONE);

    public static IncidentView from(Incident incident) {
        return new IncidentView(
                incident.getId(),
                incident.getType().displayName(),
                incident.getTitle(),
                incident.getDetail(),
                incident.getStatus(),
                incident.getStatus().displayName(),
                badgeClass(incident.getStatus()),
                incident.getOccurrenceCount(),
                format(incident.getCreatedAt()),
                format(incident.getLastOccurredAt()),
                format(incident.getResolvedAt()),
                incident.getResolutionNote(),
                incident.getStatus() == IncidentStatus.OPEN,
                incident.getStatus() != IncidentStatus.RESOLVED);
    }

    private static String badgeClass(IncidentStatus status) {
        return switch (status) {
            case OPEN -> "badge-error";
            case ACKNOWLEDGED -> "badge-warning";
            case RESOLVED -> "badge-success";
        };
    }

    private static String format(Instant instant) {
        return instant == null ? "-" : FORMATTER.format(instant);
    }
}
