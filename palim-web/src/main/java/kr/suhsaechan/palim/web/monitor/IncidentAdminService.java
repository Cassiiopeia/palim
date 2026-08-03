package kr.suhsaechan.palim.web.monitor;

import java.util.UUID;
import kr.suhsaechan.palim.audit.AuditType;
import kr.suhsaechan.palim.incident.Incident;
import kr.suhsaechan.palim.incident.IncidentService;
import kr.suhsaechan.palim.incident.IncidentStatus;
import kr.suhsaechan.palim.incident.IncidentType;
import kr.suhsaechan.palim.web.audit.WebAuditRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인시던트 화면용 조율 서비스 (#34).
 *
 * <p>{@code IncidentService} 의 변경 메서드가 {@code MANDATORY} 이므로 트랜잭션을 여는 계층이
 * 필요하다. 확인·해결은 감사 대상이다 — "누가 언제 이 사건을 처리했는지"가 인시던트 기능의
 * 존재 이유와 같은 질문이다.
 */
@Service
@RequiredArgsConstructor
public class IncidentAdminService {

    private static final String TARGET_TYPE = "INCIDENT";

    private final IncidentService incidentService;
    private final WebAuditRecorder webAuditRecorder;

    @Transactional(readOnly = true)
    public Page<IncidentView> find(IncidentStatus status, IncidentType type, Pageable pageable) {
        return incidentService.find(status, type, pageable).map(IncidentView::from);
    }

    @Transactional(readOnly = true)
    public long countUnresolved() {
        return incidentService.countUnresolved();
    }

    @Transactional
    public void acknowledge(UUID incidentId) {
        Incident incident = incidentService.get(incidentId);
        incidentService.acknowledge(incidentId);

        webAuditRecorder.recordChange(AuditType.INCIDENT_ACKNOWLEDGE, TARGET_TYPE,
                incidentId.toString(),
                "인시던트 확인 — %s".formatted(incident.getTitle()), null, null);
    }

    @Transactional
    public void resolve(UUID incidentId, String memo) {
        Incident incident = incidentService.get(incidentId);
        incidentService.resolve(incidentId, memo);

        webAuditRecorder.recordChange(AuditType.INCIDENT_RESOLVE, TARGET_TYPE,
                incidentId.toString(),
                "인시던트 해결 — %s".formatted(incident.getTitle()),
                null,
                java.util.Map.of("memo", memo == null ? "" : memo.trim()));
    }
}
