package kr.suhsaechan.palim.web.home;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.run.DiffState;
import kr.suhsaechan.palim.reconcile.run.DiffType;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiff;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiffRepository;
import kr.suhsaechan.palim.reconcile.run.ReconcileRun;
import kr.suhsaechan.palim.reconcile.run.ReconcileRunRepository;
import kr.suhsaechan.palim.reconcile.run.RunStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 홈의 「오늘 맞춰 봤습니다」.
 *
 * <p>준비 상태 계산({@link kr.suhsaechan.palim.web.setup.SetupService})과 나눈 이유는 둘의
 * 수명이 다르기 때문이다. 준비 상태는 처음 며칠만 쓰이고, 이 요약은 그 뒤로 매일 쓰인다.
 */
@Service
@RequiredArgsConstructor
public class HomeSummaryService {

    private final ReconcileDefinitionRepository definitions;
    private final ReconcileRunRepository runs;
    private final ReconcileDiffRepository diffs;

    @Transactional(readOnly = true)
    public TodayReconcile today() {
        Optional<ReconcileRun> latest = definitions.findByIsActiveTrueOrderByCode().stream()
                .map(this::latestSuccess)
                .flatMap(Optional::stream)
                .max(Comparator.comparing(ReconcileRun::getStartedAt));

        if (latest.isEmpty()) {
            return TodayReconcile.none();
        }

        ReconcileRun run = latest.get();
        List<ReconcileDiff> found = diffs.findByRunIdOrderByStateAscUnitCodeAsc(run.getId());

        // 미매칭도 관찰중으로 저장된다. 상태만 보고 세면 «재고를 맞출 일» 과 «품목을 이을 일»
        // 이 한 숫자에 섞여, 사장님이 엉뚱한 화면을 뒤지게 된다.
        int unmatched = (int) found.stream().filter(d -> isUnmatched(d.getDiffType())).count();
        int confirmed = (int) found.stream()
                .filter(d -> !isUnmatched(d.getDiffType()))
                .filter(d -> d.getState() == DiffState.CONFIRMED).count();
        int observing = (int) found.stream()
                .filter(d -> !isUnmatched(d.getDiffType()))
                .filter(d -> d.getState() == DiffState.OBSERVING).count();

        return new TodayReconcile(true, run.getStartedAt(), confirmed, observing, unmatched);
    }

    private boolean isUnmatched(DiffType type) {
        return type == DiffType.UNMATCHED_LEFT || type == DiffType.UNMATCHED_RIGHT;
    }

    private Optional<ReconcileRun> latestSuccess(ReconcileDefinition definition) {
        return runs.findFirstByDefinitionIdAndStatusOrderByStartedAtDesc(
                definition.getId(), RunStatus.SUCCESS);
    }
}
