package kr.suhsaechan.palim.incident;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    /**
     * 같은 키의 미해결 인시던트.
     *
     * <p>재발 누적의 기준이다. 해결된 행은 제외한다 — 해결 후 재발은 새 인시던트가 된다.
     * 미해결이 동시에 둘일 수 없는 것은 이 조회를 거쳐서만 생성하기 때문이며, DB 유니크로
     * 강제하지 않는다(해결된 같은 키 행이 여럿인 것이 정상이라 부분 인덱스가 필요해지는데,
     * 단일 관리자 운영에서 그 복잡도가 값어치를 못 한다).
     */
    @Query("""
            select i from Incident i
            where i.dedupeKey = :dedupeKey
              and i.status <> kr.suhsaechan.palim.incident.IncidentStatus.RESOLVED
            """)
    Optional<Incident> findUnresolvedByDedupeKey(@Param("dedupeKey") String dedupeKey);

    Page<Incident> findByStatus(IncidentStatus status, Pageable pageable);

    Page<Incident> findByStatusAndIncidentType(IncidentStatus status, IncidentType incidentType,
                                               Pageable pageable);

    Page<Incident> findByIncidentType(IncidentType incidentType, Pageable pageable);

    /** 미해결 건수. 대시보드(로드맵 5)와 화면 요약에 쓴다. */
    @Query("""
            select count(i) from Incident i
            where i.status <> kr.suhsaechan.palim.incident.IncidentStatus.RESOLVED
            """)
    long countUnresolved();

    @Query("""
            select i from Incident i
            where i.status <> kr.suhsaechan.palim.incident.IncidentStatus.RESOLVED
            """)
    List<Incident> findAllUnresolved();
}
