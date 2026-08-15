package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.connector.run.ConnectorRun;
import kr.suhsaechan.palim.connector.run.ConnectorRunRepository;
import kr.suhsaechan.palim.connector.run.RunMode;
import kr.suhsaechan.palim.connector.run.RunStatus;
import kr.suhsaechan.palim.connector.run.RunTrigger;
import kr.suhsaechan.palim.connector.run.StaleRunRecovery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 배포 한 번에 연동 하나가 <b>영영 잠기지 않는가</b>.
 *
 * <p>같은 연동이 겹쳐 도는 것을 막으려고, 실행 중인 것이 있으면 새 실행을 거부한다. 그런데
 * 앱이 <b>실행 도중에</b> 내려가면 그 기록은 끝맺지 못하고 「실행 중」으로 남는다. 배포는
 * 컨테이너를 지웠다 다시 만드는 일이라 이 상황이 반드시 온다.
 *
 * <p>그러면 그 연동은 <b>아무것도 할 수 없게 된다.</b> 화면에는 「이미 실행 중입니다. 완료 후
 * 다시 시도하세요」 만 뜨는데, 완료될 실행이 애초에 없으므로 영원히 기다리게 된다. 사람이
 * 풀 방법도 없다.
 *
 * <p>실제로 오늘 배포를 여러 번 한 뒤 이카운트 연동이 그 상태가 됐다.
 */
class StaleRunRecoveryIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private StaleRunRecovery recovery;
    @Autowired private ConnectorRunRepository runRepository;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private TargetModelRepository targetModelRepository;

    private Connector connector;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT, "std_stock_snapshot").orElseThrow();
        connector = connectorRepository.save(Connector.of(TENANT,
                "stale-" + UUID.randomUUID().toString().substring(0, 8),
                "굳은 실행 시험", model.getId(), SourceType.HTTP_API, "EA"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 기동하면 굳은 것이 풀리는가.
     *
     * <p>기동한 순간, 남아 있는 「실행 중」은 전부 죽은 것이다 — 그 실행을 하던 프로세스는
     * 이미 없다. 조용히 지우지 않고 <b>실패로 남기는</b> 이유는, 그 시각에 무엇을 하려다
     * 끊겼는지가 나중에 원인을 찾는 단서이기 때문이다.
     */
    @Test
    @DisplayName("실행 도중에 앱이 내려가도 다음 기동에 잠금이 풀린다")
    void 굳은_실행이_기동에_풀린다() {
        // 실행 도중에 앱이 내려간 상태를 그대로 만든다 — 시작만 하고 끝맺지 않았다.
        ConnectorRun stuck = runRepository.save(ConnectorRun.start(
                TENANT, connector.getId(), UUID.randomUUID(), 1,
                RunMode.TEST, RunTrigger.MANUAL));
        assertThat(stuck.getStatus()).isEqualTo(RunStatus.RUNNING);

        recovery.closeStaleRuns();

        ConnectorRun closed = runRepository.findById(stuck.getId()).orElseThrow();
        assertThat(closed.getStatus())
                .as("「실행 중」 이 남아 있으면 그 연동은 영영 잠긴다 — 완료될 실행이 없다")
                .isEqualTo(RunStatus.FAILED);
        assertThat(runRepository.existsByConnectorIdAndStatus(
                connector.getId(), RunStatus.RUNNING))
                .as("잠금이 풀려야 사람이 다시 실행할 수 있다")
                .isFalse();
    }

    /** 끝난 실행은 건드리지 않는다. 지난 기록을 실패로 바꾸면 이력이 거짓이 된다. */
    @Test
    @DisplayName("이미 끝난 실행은 손대지 않는다")
    void 끝난_실행은_그대로다() {
        ConnectorRun done = ConnectorRun.start(
                TENANT, connector.getId(), UUID.randomUUID(), 1,
                RunMode.TEST, RunTrigger.MANUAL);
        done.finish(10, 10, 0);
        runRepository.save(done);

        recovery.closeStaleRuns();

        assertThat(runRepository.findById(done.getId()).orElseThrow().getStatus())
                .as("지난 기록을 실패로 바꾸면 이력이 거짓이 된다")
                .isEqualTo(RunStatus.SUCCEEDED);
    }
}
