package kr.suhsaechan.palim.reconcile.engine;

import java.util.Optional;
import java.util.UUID;
import kr.suhsaechan.palim.reconcile.run.DiffState;
import kr.suhsaechan.palim.reconcile.run.DiffType;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiff;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiffRepository;
import kr.suhsaechan.palim.reconcile.run.ReconcileRun;
import kr.suhsaechan.palim.reconcile.run.ReconcileRunRepository;
import kr.suhsaechan.palim.reconcile.run.RunStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 차이를 <b>두 번 보고</b> 확정한다.
 *
 * <p>처음 보이는 차이는 반영 지연일 수 있다. 전산에 입력은 됐는데 물류에 아직 안 잡혔거나 그
 * 반대인 경우가 실무에서 가장 흔하고, 그런 것은 <b>다음 회차에 저절로 사라진다.</b>
 *
 * <p>첫 회차부터 알리면 매일 헛알림이 가고, 그러면 진짜 알림도 안 보게 된다. 알림이 잡음이
 * 되는 순간 그 알림은 없는 것과 같아진다.
 *
 * <p>승격 판정만 이 클래스가 안다. 합산은 {@link SnapshotAggregator}, 비교 규칙은
 * {@link ReconcileEngine} 이 안다 — 셋을 한 곳에 두면 허용 오차를 고쳤을 때 승격이 깨진다.
 */
@Component
@RequiredArgsConstructor
public class DiffPromoter {

    private final ReconcileRunRepository runs;
    private final ReconcileDiffRepository diffs;

    /**
     * 이 차이를 어떤 상태로 기록할지 정한다.
     *
     * <p>이전에 <b>같은 단위·같은 방향</b>으로 있었으면 시간으로 설명되지 않는다. 방향까지 보는
     * 이유는, 어제 전산이 많았는데 오늘 물류가 많다면 그것은 같은 문제가 아니기 때문이다.
     *
     * @return 새 상태와 처음 관찰된 실행 번호
     */
    @Transactional(readOnly = true)
    public Promotion decide(UUID definitionId, UUID currentRunId, UUID unitId, DiffType diffType) {
        if (unitId == null) {
            // 미매칭은 승격 대상이 아니다. 사람이 연결해야 풀리는 일이라 «관찰» 이라는 말이
            // 맞지 않는다.
            return new Promotion(DiffState.OBSERVING, currentRunId);
        }

        Optional<ReconcileRun> previous = runs
                .findFirstByDefinitionIdAndStatusOrderByStartedAtDesc(definitionId,
                        RunStatus.SUCCESS)
                .filter(run -> !run.getId().equals(currentRunId));

        if (previous.isEmpty()) {
            return new Promotion(DiffState.OBSERVING, currentRunId);
        }

        return diffs.findByRunIdAndUnitIdAndDiffType(previous.get().getId(), unitId, diffType)
                .map(before -> {
                    // 사람이 «알면서 둔다» 고 했으면 다음 회차에도 조용히 둔다.
                    if (before.getState() == DiffState.IGNORED) {
                        return new Promotion(DiffState.IGNORED, before.getFirstSeenRunId());
                    }
                    return new Promotion(DiffState.CONFIRMED, before.getFirstSeenRunId());
                })
                .orElseGet(() -> new Promotion(DiffState.OBSERVING, currentRunId));
    }

    /**
     * @param state          이번에 기록할 상태
     * @param firstSeenRunId 이 차이가 처음 관찰된 실행. 며칠째인지 세는 근거
     */
    public record Promotion(DiffState state, UUID firstSeenRunId) {
    }
}
