package kr.suhsaechan.palim.web.monitor;

import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.audit.AuditType;
import kr.suhsaechan.palim.incident.Incident;
import kr.suhsaechan.palim.incident.IncidentService;
import kr.suhsaechan.palim.incident.IncidentStatus;
import kr.suhsaechan.palim.web.audit.WebAuditRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인시던트 화면용 조율 서비스 (#35).
 *
 * <p>{@code IncidentService} 의 변경 메서드가 {@code MANDATORY} 이므로 트랜잭션을 여는 계층이
 * 필요하다. 상태 변경은 감사 대상이다 — "이 문제를 누가 언제 마감했는지"가 인시던트의 존재
 * 이유이므로, 마감 행위 자체도 추적 가능해야 한다.
 */
@Service
@RequiredArgsConstructor
public class IncidentAdminService {

    private final IncidentService incidentService;
    private final WebAuditRecorder webAuditRecorder;

    @Transactional(readOnly = true)
    public Page<IncidentView> findIncidents(IncidentStatus status, Pageable pageable) {
        return incidentService.findIncidents(status, pageable).map(IncidentView::from);
    }

    @Transactional
    public void acknowledge(UUID incidentId) {
        Incident incident = incidentService.get(incidentId);
        incidentService.acknowledge(incidentId);

        webAuditRecorder.recordChange(AuditType.INCIDENT_ACKNOWLEDGE,
                "INCIDENT", incidentId.toString(),
                "'%s' 인시던트를 확인 상태로 변경했습니다.".formatted(incident.getTitle()),
                null, null);
    }

    @Transactional
    public void resolve(UUID incidentId, String resolutionNote) {
        Incident incident = incidentService.get(incidentId);
        incidentService.resolve(incidentId, resolutionNote);

        // 해결 메모를 스냅샷으로 남긴다 — 나중에 "어떻게 조치했는지" 를 감사 로그에서도 볼 수 있다.
        Map<String, ?> after = resolutionNote == null
                ? null
                : Map.of("resolutionNote", resolutionNote);
        webAuditRecorder.recordChange(AuditType.INCIDENT_RESOLVE,
                "INCIDENT", incidentId.toString(),
                "'%s' 인시던트를 해결 처리했습니다.".formatted(incident.getTitle()),
                null, after);
    }
}
