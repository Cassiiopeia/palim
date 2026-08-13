package kr.suhsaechan.palim.connector.run;

import java.time.Instant;
import java.util.List;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorMappingRepository;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.MappingStatus;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 매일 자동으로 받아온다.
 *
 * <p>여기까지 와야 연동이 <b>업무가 된다.</b> 사람이 화면에 들어와 버튼을 눌러야 자료가 쌓이는
 * 구조라면, 바쁜 날 거르고 그러다 안 하게 된다.
 *
 * <p>주기는 커넥터마다 다르므로 고정 표현식을 쓸 수 없다. 대신 <b>1분마다 깨어나 지금 돌 것이
 * 있는지 확인</b>한다. 커넥터가 수십 개가 되어도 확인 비용은 조회 한 번이다.
 *
 * <p>스스로 가져오는 원천만 대상이다. 업로드 원천은 사람이 파일을 올려야 하므로 자동으로 돌
 * 방법이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorScheduler {

    private final ConnectorRepository connectors;
    private final ConnectorMappingRepository mappings;
    private final ConnectorRunner runner;
    private final ConnectorRunRepository runs;

    /**
     * 1분마다 확인한다.
     *
     * <p>실행이 오래 걸려도 다음 확인이 겹쳐 돌지 않도록 {@code fixedDelay} 를 쓴다. 같은
     * 커넥터가 두 번 동시에 도는 것이 최악이다 — 같은 자료를 두 벌 담게 된다.
     */
    @Scheduled(fixedDelayString = "${palim.connector.scheduler-delay:60000}")
    public void runDue() {
        Instant now = Instant.now();
        List<Connector> candidates = connectors.findByEnabledTrueAndScheduleCronIsNotNull();

        for (Connector connector : candidates) {
            try {
                if (shouldRun(connector, now)) {
                    execute(connector);
                }
            } catch (RuntimeException e) {
                // 하나가 실패해도 나머지는 돌아야 한다. 한 원천이 막혔다고 다른 원천의
                // 수집까지 멈추면 대조에 쓸 자료가 통째로 사라진다.
                log.error("자동 수집 실패 — connector={}", connector.getCode(), e);
            }
        }
    }

    /**
     * 지금 돌 차례인가.
     *
     * <p>확정된 연결이 없으면 돌리지 않는다. 연결을 짜다 만 상태로 자동 수집이 돌면 절반만
     * 채워진 자료가 쌓이고, 그것을 기준으로 대조가 돈다.
     */
    private boolean shouldRun(Connector connector, Instant now) {
        if (connector.getSourceType() == SourceType.UPLOAD) {
            return false;
        }
        if (!StringUtils.hasText(connector.getScheduleCron())) {
            return false;
        }
        if (mappings.findByConnectorIdAndStatus(connector.getId(), MappingStatus.ACTIVE)
                .isEmpty()) {
            return false;
        }
        return due(connector, now);
    }

    /**
     * 지난 확인 이후로 예정 시각이 지났는가.
     *
     * <p>«지금이 예정 시각인가» 로 판단하면 확인 주기와 어긋나 영영 걸리지 않는다. 대신 <b>직전
     * 확인 시점부터 지금까지 사이에</b> 예정 시각이 있었는지 본다.
     */
    private boolean due(Connector connector, Instant now) {
        CronExpression cron = CronExpression.parse(connector.getScheduleCron());
        Instant lastRun = runs.findFirstByConnectorIdAndRunModeOrderByStartedAtDesc(
                        connector.getId(), RunMode.LIVE)
                .map(ConnectorRun::getStartedAt)
                .orElse(null);
        // 한 번도 안 돌았으면 직전 1분만 본다. 처음 켰다고 과거 예정분을 몰아서 돌리면
        // 같은 자료를 여러 번 담게 된다.
        Instant since = lastRun == null ? now.minusSeconds(60) : lastRun;

        var next = cron.next(since.atZone(java.time.ZoneId.of("Asia/Seoul")));
        return next != null && !next.toInstant().isAfter(now);
    }

    private void execute(Connector connector) {
        TenantContext.set(connector.getTenantId());
        try {
            ConnectorRun run = runner.run(new RunRequest(connector.getId(), RunMode.LIVE,
                    RunTrigger.SCHEDULED, null, 1));
            log.info("자동 수집 완료 — connector={} 총 {}건 성공 {}건 실패 {}건",
                    connector.getCode(), run.getTotalCount(), run.getSuccessCount(),
                    run.getFailedCount());
        } finally {
            TenantContext.clear();
        }
    }
}
