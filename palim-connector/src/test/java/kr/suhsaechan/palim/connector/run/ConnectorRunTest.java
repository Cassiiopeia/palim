package kr.suhsaechan.palim.connector.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 실행 상태 전이 검증. 부분 실패를 어떻게 표시하느냐가 이 클래스의 전부다. */
class ConnectorRunTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    @DisplayName("실패 행이 하나도 없으면 SUCCEEDED 다")
    void 전부_성공하면_SUCCEEDED() {
        ConnectorRun run = newRun();

        run.finish(100, 100, 0);

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("실패 행이 하나라도 있으면 PARTIAL 이다")
    void 일부_실패하면_PARTIAL() {
        ConnectorRun run = newRun();

        run.finish(100, 97, 3);

        assertThat(run.getStatus()).isEqualTo(RunStatus.PARTIAL);
        assertThat(run.getSuccessCount()).isEqualTo(97);
        assertThat(run.getFailedCount()).isEqualTo(3);
    }

    private ConnectorRun newRun() {
        return ConnectorRun.start(TENANT, UUID.randomUUID(), UUID.randomUUID(), 1,
                RunMode.TEST, RunTrigger.MANUAL);
    }
}
