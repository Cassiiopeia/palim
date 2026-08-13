package kr.suhsaechan.palim.reconcile.engine;

import java.util.List;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiff;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiffRepository;
import kr.suhsaechan.palim.reconcile.run.ReconcileRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 한 번 스스로 맞춰 본다.
 *
 * <p>사람이 화면에 들어와 버튼을 눌러야 돌아가는 구조라면 바쁜 날 거르고, 그러다 안 하게 된다.
 * 자동 수집까지 만들어 두고 대조만 수동으로 남기면 <b>절반만 자동인 셈</b>이라 결국 사람 손을
 * 탄다.
 *
 * <p><b>수집보다 늦게 돈다.</b> 순서가 뒤집히면 어제 자료로 맞춰 보게 되고, 그 결과는 매일
 * 어긋난다. 수집 스케줄러가 새벽에 돌므로 여기는 그 뒤로 잡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcileScheduler {

    private final ReconcileDefinitionRepository definitions;
    private final ReconcileDiffRepository diffs;
    private final ReconcileEngine engine;
    private final ReconcileAlertPolicy alertPolicy;
    private final ReconcileNotifier notifier;

    /**
     * 매일 아침에 맞춰 본다.
     *
     * <p>시각을 설정으로 뺀 이유는 수집이 언제 끝나는지가 원천마다 다르기 때문이다. 수집이
     * 아직 도는 중에 대조가 시작되면 절반만 담긴 자료를 비교하게 된다.
     */
    @Scheduled(cron = "${palim.reconcile.cron:0 30 6 * * *}", zone = "Asia/Seoul")
    public void runAll() {
        List<ReconcileDefinition> targets = definitions.findByIsActiveTrueOrderByCode();
        for (ReconcileDefinition definition : targets) {
            try {
                runOne(definition);
            } catch (RuntimeException e) {
                // 하나가 실패해도 나머지는 돌아야 한다. 한 대조가 막혔다고 다른 대조까지
                // 멈추면 볼 수 있었을 문제도 못 본다.
                log.error("자동 대조 실패 — definition={}", definition.getCode(), e);
            }
        }
    }

    private void runOne(ReconcileDefinition definition) {
        TenantContext.set(definition.getTenantId());
        try {
            ReconcileRun run = engine.run(definition.getId());

            if (!run.isSuccess()) {
                // 기준 시각이 어긋난 것은 «다음 회차에 다시 해 보면 되는» 일이다. 여기서
                // 알림까지 보내면 수집이 늦은 날마다 알림이 간다.
                log.warn("자동 대조를 건너뛴다 — definition={} 사유={}",
                        definition.getCode(), run.getMessage());
                return;
            }

            List<ReconcileDiff> found =
                    diffs.findByRunIdOrderByStateAscUnitCodeAsc(run.getId());
            List<ReconcileDiff> alertable = alertPolicy.selectAlertable(definition, found);

            if (!alertable.isEmpty()) {
                notifier.notifyMismatch(definition, run, alertable);
            }
            log.info("자동 대조 완료 — definition={} 차이 {}건 중 알릴 것 {}건",
                    definition.getCode(), run.getDiffCount(), alertable.size());
        } finally {
            TenantContext.clear();
        }
    }
}
