package kr.suhsaechan.palim.reconcile.run;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/** 대조 실행 저장소. */
public interface ReconcileRunRepository extends JpaRepository<ReconcileRun, UUID> {

    List<ReconcileRun> findByDefinitionIdOrderByStartedAtDesc(UUID definitionId);

    /**
     * 그 시각 이후에 <b>저절로</b> 돈 회차가 있는가.
     *
     * <p>사람이 누른 회차를 빼고 세는 것이 핵심이다. 섞어 세면 아침에 한 번 「지금 맞춰 보기」
     * 를 누른 것 때문에 그날 자동 대조가 통째로 건너뛰어진다.
     *
     * <p>판정을 DB 로 하는 이유 — 배포할 때마다 새로 뜨므로 기억해 둔 표시는 사라진다.
     */
    boolean existsByTriggerTypeAndStartedAtAfter(ReconcileTrigger triggerType, Instant startedAt);

    /**
     * 최근 회차 몇 개만.
     *
     * <p>상한을 <b>SQL 에 건다.</b> 자바 반복문에서만 끊으면 조회는 여전히 전체 이력을 읽어
     * 오므로, 한 번도 성공한 적 없는 대조는 매일 아침 이력 전체를 통째로 읽게 된다.
     */
    List<ReconcileRun> findByDefinitionIdOrderByStartedAtDesc(UUID definitionId, Limit limit);

    /** 승격 판정에 쓴다 — 직전에 성공한 실행과 견준다. */
    Optional<ReconcileRun> findFirstByDefinitionIdAndStatusOrderByStartedAtDesc(
            UUID definitionId, RunStatus status);
}
