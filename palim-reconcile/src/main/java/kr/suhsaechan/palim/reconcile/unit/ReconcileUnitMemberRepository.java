package kr.suhsaechan.palim.reconcile.unit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 원천 품목 ↔ 정합 단위 연결 저장소. */
public interface ReconcileUnitMemberRepository
        extends JpaRepository<ReconcileUnitMember, UUID> {

    /** 이 품목이 이미 어딘가에 붙어 있나. 두 단위에 붙으면 수량이 두 번 세어진다. */
    Optional<ReconcileUnitMember> findBySourceAndItemRef(String source, String itemRef);

    List<ReconcileUnitMember> findByUnitIdOrderBySource(UUID unitId);

    /** 아직 확인하지 않은 제안들. 매칭 화면이 이것을 보여준다. */
    List<ReconcileUnitMember> findByConfirmedAtIsNullOrderBySource();

    /** 이 단위에서 아직 확인하지 않은 것들. 확인은 <b>단위 단위</b>로 한다. */
    List<ReconcileUnitMember> findByUnitIdAndConfirmedAtIsNull(UUID unitId);

    /** 아직 확인하지 않은 제안이 걸려 있는 단위들. */
    @org.springframework.data.jpa.repository.Query(
            "select distinct m.unitId from ReconcileUnitMember m where m.confirmedAt is null")
    List<UUID> findUnitIdsWithPending();
}
