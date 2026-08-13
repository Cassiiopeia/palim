package kr.suhsaechan.palim.reconcile.run;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 대조 실행 저장소. */
public interface ReconcileRunRepository extends JpaRepository<ReconcileRun, UUID> {

    List<ReconcileRun> findByDefinitionIdOrderByStartedAtDesc(UUID definitionId);

    /** 승격 판정에 쓴다 — 직전에 성공한 실행과 견준다. */
    Optional<ReconcileRun> findFirstByDefinitionIdAndStatusOrderByStartedAtDesc(
            UUID definitionId, RunStatus status);
}
