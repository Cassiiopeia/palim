package kr.suhsaechan.palim.incident;

import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인시던트 서비스 (#35).
 *
 * <p>{@link #report} 는 감지 지점(수집·감시)의 트랜잭션에 참여한다. 수집이 롤백되면
 * 인시던트도 함께 사라져야 한다 — 실제로 반영되지 않은 주문의 문제를 기록하면 유령
 * 인시던트가 된다.
 */
@Service
@RequiredArgsConstructor
public class IncidentService {

    private final IncidentRepository incidentRepository;

    /**
     * 문제를 보고한다. 미해결 건이 있으면 발생 횟수를 누적하고, 없으면 새로 연다.
     *
     * <p>알림 억제({@code enqueueIfNotRecent})와 달리 <b>매 발생을 누적한다</b> — 알림은
     * 스팸 방지가 목적이고 인시던트는 기록이 목적이다.
     *
     * <p>조회-후-삽입 사이의 경합은 부분 유니크 인덱스가 최종 방어한다. 충돌하면 호출자의
     * 트랜잭션이 롤백되지만, 수집은 커서 미전진으로 다음 주기에 재시도하고 감시는 다음
     * 주기에 재검사하므로 자가 회복된다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Incident report(IncidentType type, String dedupeKey, String title, String detail) {
        return incidentRepository.findByDedupeKeyAndStatusNot(dedupeKey, IncidentStatus.RESOLVED)
                .map(incident -> {
                    incident.recordRecurrence(detail, Instant.now());
                    return incident;
                })
                .orElseGet(() -> incidentRepository.save(
                        Incident.open(type, dedupeKey, title, detail, Instant.now())));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acknowledge(UUID incidentId) {
        get(incidentId).acknowledge();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resolve(UUID incidentId, String resolutionNote) {
        get(incidentId).resolve(resolutionNote);
    }

    @Transactional(readOnly = true)
    public Incident get(UUID incidentId) {
        return incidentRepository.findById(incidentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INCIDENT_NOT_FOUND, incidentId));
    }

    /** 화면 목록. {@code status} 가 null 이면 전체. */
    @Transactional(readOnly = true)
    public Page<Incident> findIncidents(IncidentStatus status, Pageable pageable) {
        return status == null
                ? incidentRepository.findAll(pageable)
                : incidentRepository.findByStatus(status, pageable);
    }

    /** 미해결(미확인 + 확인) 건수. 대시보드(로드맵 5)가 쓸 요약 값이다. */
    @Transactional(readOnly = true)
    public long countUnresolved() {
        return incidentRepository.countByStatusNot(IncidentStatus.RESOLVED);
    }
}
