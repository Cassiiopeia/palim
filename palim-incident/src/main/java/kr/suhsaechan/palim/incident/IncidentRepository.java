package kr.suhsaechan.palim.incident;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    /**
     * 미해결 건 조회 + 비관적 락.
     *
     * <p>{@code report} 가 누적 갱신하는 행이다. 락 없이 두 감지 트랜잭션이 같은 행을 갱신하면
     * 낙관적 락 충돌로 수집 트랜잭션 전체가 굴러떨어진다 — 재고 차감이 롤백되는 값이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Incident> findByDedupeKeyAndStatusNot(String dedupeKey, IncidentStatus status);

    Page<Incident> findByStatus(IncidentStatus status, Pageable pageable);

    long countByStatusNot(IncidentStatus status);
}
