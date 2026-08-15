package kr.suhsaechan.palim.reconcile.engine;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiff;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiffRepository;
import kr.suhsaechan.palim.reconcile.run.ReconcileRun;
import kr.suhsaechan.palim.reconcile.run.ReconcileRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
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

    /**
     * <b>며칠</b> 연속 실패하면 사람을 부를 것인가.
     *
     * <p>하루·이틀은 수집이 늦어 기준 시각이 어긋난 것일 수 있지만, 사흘이면 저절로 풀리는
     * 종류가 아니다.
     *
     * <p><b>회차가 아니라 날짜를 센다.</b> 실행 이력은 스케줄러만 쓰는 것이 아니라 화면의
     * 「지금 맞춰 보기」 와 공유한다. 회차로 세면 사람이 오전에 세 번 눌러 본 것만으로
     * 「사흘째 막혔다」 는 알림이 나가고, 반대로 세는 자리에 따라 문턱을 건너뛸 수도 있다.
     */
    private static final int FAILURE_DAYS_TO_ALERT = 3;

    /**
     * 실패 이력을 훑을 최대 회차.
     *
     * <p>한 번도 성공한 적 없는 대조는 이력 전체가 실패라, 안 막으면 이력이 쌓일수록 매일
     * 아침 조회가 무거워진다. 며칠인지만 알면 되므로 넉넉히 이만큼만 본다 — 하루에 수십 번
     * 눌러 보는 일은 없다.
     */
    private static final int FAILURE_SCAN_LIMIT = 60;

    /** 「며칠째인가」 를 가르는 지역. 코드베이스 다른 곳과 같은 값이어야 한다. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final ReconcileDefinitionRepository definitions;
    private final ReconcileDiffRepository diffs;
    private final ReconcileRunRepository runs;
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
                handleFailure(definition, run);
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

    /**
     * 못 돈 회차를 어떻게 다룰 것인가.
     *
     * <p>기준 시각이 어긋난 것은 <b>다음 회차에 다시 해 보면 되는</b> 일이다. 수집이 늦은
     * 날마다 알리면 사람이 알림 자체를 꺼 버리고, 그러면 정작 봐야 할 것도 못 본다. 그래서
     * 하루짜리 실패는 예전처럼 로그에만 남긴다.
     *
     * <p><b>그런데 영영 안 풀리는 실패도 똑같이 생겼다.</b> 설정이 깨졌거나 한쪽 수집이 멈춘
     * 경우인데, 지금까지는 이 자리에서 그냥 돌아섰기 때문에 <b>몇 주를 안 돌아도 아무도
     * 몰랐다.</b> 조용한 실패를 막으려고 만든 장치들이 정작 이 문 앞에서 전부 조용해졌다.
     *
     * <p>그래서 <b>연속 실패를 센다.</b> 며칠 연속이면 「다시 하면 되는 일」 이 아니다.
     *
     * <p>문턱을 <b>「정확히 같을 때」 가 아니라 「넘었을 때」</b> 로 본다. 정확히 같을 때만
     * 알리면 셈이 한 번이라도 건너뛰는 순간 영영 안 알린다 — 사람이 화면에서 「지금 맞춰
     * 보기」 를 한 번 누르면 그 실패도 이력에 쌓여 셈이 2에서 4로 뛴다. 그러면 이 장치가
     * 조용해지는데, <b>조용한 실패를 막으려고 만든 장치가 조용히 죽는</b> 것이야말로 이
     * 작업이 없애려던 바로 그 모양이다.
     *
     * <p>대신 소음은 <b>알림 쪽의 억제 기간</b>이 막는다. 넘은 뒤로는 한동안 다시 부르지
     * 않고, 그 기간이 지나도록 안 고쳐졌으면 한 번 더 부른다 — 안 고쳐진 문제를 영영
     * 침묵시키는 것도 옳지 않다. 성공하면 셈이 리셋된다.
     */
    private void handleFailure(ReconcileDefinition definition, ReconcileRun run) {
        int failedDays = consecutiveFailedDays(definition.getId());

        if (failedDays >= FAILURE_DAYS_TO_ALERT) {
            log.error("자동 대조가 {}일째 막혔다 — definition={} 사유={}",
                    failedDays, definition.getCode(), run.getMessage());
            notifier.notifyBlocked(definition, run, failedDays);
            return;
        }
        log.warn("자동 대조를 건너뛴다 — definition={} {}일째 사유={}",
                definition.getCode(), failedDays, run.getMessage());
    }

    /**
     * 최근 회차를 새것부터 훑어 <b>성공을 만나기 전까지 며칠</b>이 걸쳐 있는지 센다.
     *
     * <p>회차가 아니라 <b>서로 다른 날</b>을 센다. 같은 날 여러 번 실패한 것은 하루다 —
     * 사람이 화면에서 몇 번 눌러 봤다고 「사흘째」 가 되면 안 되고, 반대로 그 눌러 본 회차
     * 때문에 문턱을 건너뛰어도 안 된다.
     *
     * <p>연속 실패를 어디에도 저장하지 않는 이유는 실행 이력이 이미 그 사실을 갖고 있기
     * 때문이다. 따로 세어 두면 두 값이 갈라질 수 있고, 갈라진 쪽이 어느 쪽인지 알 방법이 없다.
     */
    private int consecutiveFailedDays(UUID definitionId) {
        Set<LocalDate> days = new HashSet<>();
        for (ReconcileRun past : runs.findByDefinitionIdOrderByStartedAtDesc(
                definitionId, Limit.of(FAILURE_SCAN_LIMIT))) {
            if (past.isSuccess()) {
                break;
            }
            days.add(LocalDate.ofInstant(past.getStartedAt(), BUSINESS_ZONE));
        }
        return days.size();
    }
}
