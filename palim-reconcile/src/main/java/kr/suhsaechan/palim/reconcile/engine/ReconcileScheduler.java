package kr.suhsaechan.palim.reconcile.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.common.config.ConfigReader;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiff;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiffRepository;
import kr.suhsaechan.palim.reconcile.run.ReconcileRun;
import kr.suhsaechan.palim.reconcile.run.ReconcileTrigger;
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
    private final ReconcileDigestAssembler digestAssembler;
    private final ConfigReader config;

    /**
     * 짧게 자주 깨어나 <b>정한 시각이 지났는지</b> 본다.
     *
     * <h2>왜 시각을 직접 걸지 않는가</h2>
     *
     * <p>{@code @Scheduled(cron)} 의 시각 표현은 <b>기동할 때 한 번</b> 읽혀 등록된다. 그래서
     * 화면에서 시각을 바꿔도 다음 재기동까지 먹지 않는다 — 원리적으로 불가능하다.
     *
     * <p>그런데 이 값은 화면에서 바뀌어야 한다. 수집이 언제 끝나는지가 원천마다 다르고,
     * 그 사정은 쓰면서 바뀐다. 예전에는 그 설정 키가 <b>어떤 설정 파일에도 없어서</b> 바꾸려면
     * 다시 배포해야 했다.
     *
     * <p>그래서 자주 깨어나 「지났나」 를 본다 — 일일 리포트가 같은 이유로 같은 방식을 쓴다.
     *
     * <h2>두 번 돌지 않게</h2>
     *
     * <p>시각이 지난 뒤로는 깨어날 때마다 조건이 참이다. 「오늘 저절로 돈 회차가 있는가」 를
     * 이력으로 확인해 막는다. 사람이 누른 회차는 <b>세지 않는다</b> — 한 번 눌렀다는 이유로
     * 그날 자동 대조가 건너뛰어지면 안 된다.
     */
    @Scheduled(fixedDelayString = "${palim.reconcile.poll-delay:60000}")
    public void tick() {
        LocalTime scheduled = scheduledTime();
        if (scheduled == null) {
            return;
        }
        LocalTime now = LocalTime.now(BUSINESS_ZONE);
        if (now.isBefore(scheduled)) {
            return;
        }
        Instant startOfDay = LocalDate.now(BUSINESS_ZONE)
                .atStartOfDay(BUSINESS_ZONE).toInstant();
        if (runs.existsByTriggerTypeAndStartedAtAfter(ReconcileTrigger.SCHEDULED, startOfDay)) {
            return;
        }
        log.info("정기 대조 시작 — 정한 시각 {}", scheduled);
        runAll();
    }

    /**
     * 정한 시각. 아직 읽을 수 없으면 {@code null}.
     *
     * <p><b>기동 직후에는 설정이 아직 없다.</b> 설정을 DB 에 심는 초기화는 애플리케이션이 뜬
     * 뒤에 돌고 그 트랜잭션이 커밋되기까지 잠깐 틈이 있는데, 이 확인은 <b>뜨자마자</b> 시작한다.
     * 그 사이에 읽으면 「없는 설정」 으로 터진다.
     *
     * <p>터뜨리지 않고 넘긴다. 다음 주기(1분)면 이미 심겨 있고, 정기 대조는 아침 한 번 도는
     * 일이라 1분 늦어도 아무 일이 없다. 반대로 여기서 터뜨리면 <b>기동할 때마다 오류가 찍혀</b>
     * 진짜 문제와 구분되지 않는다.
     */
    private LocalTime scheduledTime() {
        try {
            return LocalTime.of(config.getInt(ReconcileScheduleKeys.HOUR),
                    config.getInt(ReconcileScheduleKeys.MINUTE));
        } catch (RuntimeException e) {
            log.debug("정기 대조 시각을 아직 읽을 수 없다 — 다음 주기에 다시 본다");
            return null;
        }
    }

    /** 지금 곧바로 전부 맞춰 본다. 시각 판정 없이 도는 경로다. */
    public void runAll() {
        runAll(LocalDate.now(BUSINESS_ZONE).minusDays(1));
    }

    /**
     * 그 날짜 자료로 전부 맞춰 본다.
     *
     * <p>정기 실행은 <b>어제</b>를 본다. 출고 입력이 하루 늦게 마감되므로 오늘 것을 보면
     * 아직 안 들어온 분만큼 어긋난다.
     */
    public void runAll(LocalDate targetDate) {
        List<ReconcileDefinition> targets = definitions.findByIsActiveTrueOrderByCode();
        List<DefinitionOutcome> outcomes = new ArrayList<>();
        for (ReconcileDefinition definition : targets) {
            try {
                outcomes.add(runOne(definition, targetDate));
            } catch (RuntimeException e) {
                // 하나가 실패해도 나머지는 돌아야 한다. 한 대조가 막혔다고 다른 대조까지
                // 멈추면 볼 수 있었을 문제도 못 본다.
                log.error("자동 대조 실패 — definition={}", definition.getCode(), e);
                outcomes.add(DefinitionOutcome.failed(definition, null, 0));
            }
        }
        sendDigest(targetDate, targets, outcomes);
    }

    /**
     * 하루 한 통을 <b>루프가 끝난 뒤</b> 보낸다.
     *
     * <p>루프 «안» 에서 보내면 통이 대조 수만큼 갈린다. 그리고 이상이 하나도 없어도 보낸다 —
     * 안 오는 것이 「깨끗함」 인지 「멈춤」 인지 구분되지 않으면 「열지 않고 판단」 이 성립하지
     * 않는다.
     */
    private void sendDigest(LocalDate targetDate, List<ReconcileDefinition> targets,
                            List<DefinitionOutcome> outcomes) {
        if (targets.isEmpty()) {
            // 볼 대조가 하나도 없으면 보낼 것도 없다. 이때 「이상 없음」 을 보내면 «설정을
            // 안 한 것» 이 «정상» 으로 읽힌다.
            log.info("자동 대조 — 활성 대조가 없다");
            return;
        }
        TenantContext.set(targets.getFirst().getTenantId());
        try {
            notifier.notifyDigest(targetDate, digestAssembler.assemble(targetDate, outcomes));
        } catch (RuntimeException e) {
            // 요약을 못 보내도 대조 자체는 이미 끝났다. 여기서 터뜨리면 그 사실까지 잃는다.
            log.error("대조 요약을 보내지 못했다", e);
        } finally {
            TenantContext.clear();
        }
    }

    private DefinitionOutcome runOne(ReconcileDefinition definition, LocalDate targetDate) {
        TenantContext.set(definition.getTenantId());
        try {
            // 어제 자료를 본다. 안 좁히면 아침에 도는 대조가 «오늘 새벽에 들어온 것» 을
            // 견주는데, 그것은 틀린 값이 아니라 «기준이 다른 값» 이라 눈치채지 못한다.
            ReconcileRun run = engine.run(definition.getId(), ReconcileTrigger.SCHEDULED,
                    targetDate);

            if (!run.isSuccess()) {
                return handleFailure(definition, run);
            }

            List<ReconcileDiff> found =
                    diffs.findByRunIdOrderByStateAscUnitCodeAsc(run.getId());
            List<ReconcileDiff> alertable = alertPolicy.selectAlertable(definition, found);

            // 건별 알림은 보내지 않는다. 하루 한 통으로 접어 루프 밖에서 한 번만 보낸다 —
            // 여기서 보내면 통이 대조 수만큼 갈린다.
            log.info("자동 대조 완료 — definition={} 차이 {}건 중 알릴 것 {}건",
                    definition.getCode(), run.getDiffCount(), alertable.size());
            return DefinitionOutcome.succeeded(definition, run, alertable.size());
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
    private DefinitionOutcome handleFailure(ReconcileDefinition definition, ReconcileRun run) {
        int failedDays = consecutiveFailedDays(definition.getId());

        if (failedDays >= FAILURE_DAYS_TO_ALERT) {
            log.error("자동 대조가 {}일째 막혔다 — definition={} 사유={}",
                    failedDays, definition.getCode(), run.getMessage());
        } else {
            log.warn("자동 대조를 건너뛴다 — definition={} {}일째 사유={}",
                    definition.getCode(), failedDays, run.getMessage());
        }
        // 며칠째든 요약에는 담는다. 문턱 아래라고 «없는 일» 로 두면 그날 아침 요약이 아무 말도
        // 하지 않는데, 실제로는 대조가 안 돈 것이다.
        return DefinitionOutcome.failed(definition, run, failedDays);
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
