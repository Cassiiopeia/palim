package kr.suhsaechan.palim.reconcile.run;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 차이 저장소. */
public interface ReconcileDiffRepository extends JpaRepository<ReconcileDiff, UUID> {

    List<ReconcileDiff> findByRunIdOrderByStateAscUnitCodeAsc(UUID runId);

    /** 이전 실행에서 같은 단위·같은 방향 차이를 찾는다. 승격 판정의 핵심 조회다. */
    Optional<ReconcileDiff> findByRunIdAndUnitIdAndDiffType(UUID runId, UUID unitId,
                                                            DiffType diffType);

    List<ReconcileDiff> findByRunIdAndState(UUID runId, DiffState state);
}
