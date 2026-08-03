package kr.suhsaechan.palim.incident;

import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인시던트 도메인 서비스 (#34).
 *
 * <h2>{@link #report} 는 별도 트랜잭션이며 예외를 삼킨다</h2>
 *
 * <p>인시던트 기록은 주문 수집·소급 반영 같은 본 작업의 부수 기록이다. 기록 실패로 <b>수집이
 * 롤백되면 주문이 유실된다</b> — 감사 로그와 같은 원칙(07-DECISIONS 018)이다. 실패는
 * 애플리케이션 로그에 남고, 같은 사건이 재발하면 다음 발생에서 다시 기록될 기회가 있다.
 *
 * <h2>다른 도메인을 의존하지 않는다</h2>
 *
 * <p>모든 조율 계층이 호출하므로 특정 도메인을 참조하면 순환이 생긴다. 대상은 문자열
 * {@code dedupeKey} 와 표시 문자열로만 받는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentService {

    private final IncidentRepository incidentRepository;

    /**
     * 사건을 보고한다.
     *
     * <p>같은 키의 미해결 인시던트가 있으면 새로 만들지 않고 발생 횟수를 누적한다. 주문마다
     * 새 인시던트가 되면 목록이 스팸이 되어 알림과 똑같은 실패를 반복한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void report(IncidentType type, String dedupeKey, String title, String detail) {
        try {
            Instant now = Instant.now();
            incidentRepository.findUnresolvedByDedupeKey(dedupeKey)
                    .ifPresentOrElse(
                            existing -> existing.recordRecurrence(title, detail, now),
                            () -> incidentRepository.save(
                                    Incident.open(type, title, detail, dedupeKey, now)));
        } catch (RuntimeException exception) {
            // 여기서 예외를 올리면 본 작업(수집·소급 반영)이 롤백된다. 로그로만 남긴다.
            log.error("인시던트 기록 실패 — {} {}", type, dedupeKey, exception);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acknowledge(UUID incidentId) {
        get(incidentId).acknowledge(Instant.now());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resolve(UUID incidentId, String memo) {
        get(incidentId).resolve(memo, Instant.now());
    }

    @Transactional(readOnly = true)
    public Incident get(UUID incidentId) {
        return incidentRepository.findById(incidentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INCIDENT_NOT_FOUND, incidentId));
    }

    /**
     * 목록 조회. 미해결 우선이 아니라 최근 발생순으로 고정한다 — 상태 필터가 탭으로 있으므로
     * 정렬까지 상태를 섞으면 탭 안 순서가 예측 불가능해진다.
     */
    @Transactional(readOnly = true)
    public Page<Incident> find(IncidentStatus status, IncidentType type, Pageable pageable) {
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "lastOccurredAt"));

        if (status != null && type != null) {
            return incidentRepository.findByStatusAndIncidentType(status, type, sorted);
        }
        if (status != null) {
            return incidentRepository.findByStatus(status, sorted);
        }
        if (type != null) {
            return incidentRepository.findByIncidentType(type, sorted);
        }
        return incidentRepository.findAll(sorted);
    }

    @Transactional(readOnly = true)
    public long countUnresolved() {
        return incidentRepository.countUnresolved();
    }
}
