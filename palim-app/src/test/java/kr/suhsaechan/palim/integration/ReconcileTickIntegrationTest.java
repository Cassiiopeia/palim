package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import kr.suhsaechan.palim.common.config.SystemConfigService;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.engine.ReconcileScheduleKeys;
import kr.suhsaechan.palim.reconcile.engine.ReconcileScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 정한 시각을 <b>읽을 수 없을 때</b> 어떻게 하는가.
 *
 * <p>이 확인은 애플리케이션이 뜨자마자 시작하는데, 설정을 DB 에 심는 초기화는 그 뒤에 돌고
 * 트랜잭션이 커밋되기까지 잠깐 틈이 있다. 그 사이에 읽으면 「없는 설정」 으로 터진다.
 *
 * <p>실제로 배포하고 나서 <b>기동할 때마다 오류가 찍히는 것</b>을 로그에서 발견했다. 터뜨리면
 * 진짜 문제와 구분되지 않으므로 조용히 넘기고 다음 주기에 다시 본다.
 */
class ReconcileTickIntegrationTest extends IntegrationTest {

    @Autowired private ReconcileScheduler scheduler;
    @Autowired private SystemConfigService configService;
    @Autowired private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        TenantContext.set(java.util.UUID.fromString("00000000-0000-7000-8000-000000000001"));
    }

    @AfterEach
    void restore() {
        // 다른 시험이 이 설정을 쓴다. 지운 채로 두면 그쪽이 애먼 데서 터진다.
        jdbcClient.sql("DELETE FROM system_config WHERE config_key LIKE 'reconcile.schedule%'")
                .update();
        configService.reload();
        TenantContext.clear();
    }

    @Test
    @DisplayName("정한 시각을 아직 못 읽어도 터지지 않는다")
    void survivesMissingConfig() {
        jdbcClient.sql("DELETE FROM system_config WHERE config_key LIKE 'reconcile.schedule%'")
                .update();
        configService.reload();

        assertThatCode(scheduler::tick)
                .as("기동할 때마다 오류가 찍히면 진짜 문제와 구분되지 않는다")
                .doesNotThrowAnyException();
    }

    /** 설정이 있으면 정상으로 읽는다 — 위 시험이 「그냥 아무것도 안 함」 으로 통과하지 않게. */
    @Test
    @DisplayName("설정이 있으면 그 시각을 쓴다")
    void usesConfiguredTime() {
        configService.update(ReconcileScheduleKeys.HOUR, "7", "test");
        configService.update(ReconcileScheduleKeys.MINUTE, "0", "test");

        assertThat(configService.getInt(ReconcileScheduleKeys.HOUR)).isEqualTo(7);
        assertThatCode(scheduler::tick).doesNotThrowAnyException();
    }
}
