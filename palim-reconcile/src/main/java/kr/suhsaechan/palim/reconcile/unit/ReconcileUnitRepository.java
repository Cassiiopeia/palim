package kr.suhsaechan.palim.reconcile.unit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 정합 단위 저장소.
 *
 * <p>테넌트 격리는 Hibernate 필터가 자동으로 건다. 조회 메서드에 tenantId 를 넣지 않는 이유다 —
 * 각 쿼리에 맡기면 반드시 빠뜨리는 곳이 생기고, 그것이 곧 자료 유출이다.
 */
public interface ReconcileUnitRepository extends JpaRepository<ReconcileUnit, UUID> {

    Optional<ReconcileUnit> findByCode(String code);

    List<ReconcileUnit> findByIsActiveTrueOrderByCode();
}
