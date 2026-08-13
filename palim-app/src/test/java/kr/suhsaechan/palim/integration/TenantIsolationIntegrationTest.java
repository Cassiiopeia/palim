package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.common.tenant.TenantScopedRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 테넌트 격리.
 *
 * <p>지금은 테넌트가 하나라 "돌아간다"만으로는 아무것도 증명되지 않는다. <b>두 번째 테넌트를
 * 실제로 만들어</b> 서로의 데이터가 보이지 않는지 확인한다 — 이 검증이 없으면 SaaS 로 전환하는
 * 날 데이터가 섞인 채 발견된다.
 */
class TenantIsolationIntegrationTest extends IntegrationTest {

    private static final UUID TENANT_A = TenantContext.DEFAULT_TENANT_ID;

    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private TargetModelRepository targetModelRepository;
    @Autowired private JdbcClient jdbcClient;

    @AfterEach
    void tearDown() {
        // 스레드 풀에서 스레드가 재사용된다. 남겨두면 다음 테스트가 남의 테넌트로 조회한다.
        TenantContext.clear();
    }

    @Test
    @DisplayName("다른 테넌트의 커넥터는 조회되지 않는다")
    void 테넌트가_다르면_보이지_않는다() {
        UUID tenantB = newTenant();
        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT_A, "std_stock_snapshot").orElseThrow();

        String codeA = "iso-a-" + UUID.randomUUID();
        String codeB = "iso-b-" + UUID.randomUUID();
        connectorRepository.save(Connector.of(TENANT_A, codeA, "A 커넥터", model.getId(),
                SourceType.UPLOAD, "EA"));
        connectorRepository.save(Connector.of(tenantB, codeB, "B 커넥터", model.getId(),
                SourceType.UPLOAD, "EA"));

        // 기본 테넌트로 조회하면 B 는 보이지 않아야 한다
        List<String> visibleToA = connectorRepository.findAll().stream()
                .map(Connector::getCode).toList();

        assertThat(visibleToA).contains(codeA);
        assertThat(visibleToA)
                .as("필터가 없으면 SaaS 전환일에 데이터가 섞인 채 발견된다")
                .doesNotContain(codeB);
    }

    @Test
    @DisplayName("테넌트를 바꾸면 그쪽 데이터만 보인다")
    void 테넌트를_바꾸면_시야가_바뀐다() {
        UUID tenantB = newTenant();
        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT_A, "std_stock_snapshot").orElseThrow();

        String codeA = "sw-a-" + UUID.randomUUID();
        String codeB = "sw-b-" + UUID.randomUUID();
        connectorRepository.save(Connector.of(TENANT_A, codeA, "A", model.getId(),
                SourceType.UPLOAD, "EA"));
        connectorRepository.save(Connector.of(tenantB, codeB, "B", model.getId(),
                SourceType.UPLOAD, "EA"));

        TenantContext.set(tenantB);
        List<String> visibleToB = connectorRepository.findAll().stream()
                .map(Connector::getCode).toList();

        assertThat(visibleToB).contains(codeB).doesNotContain(codeA);
    }

    @Test
    @DisplayName("JdbcClient 조회에도 테넌트 조건이 강제된다")
    void read_model_도_격리된다() {
        UUID tenantB = newTenant();
        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT_A, "std_stock_snapshot").orElseThrow();
        String codeB = "jdbc-b-" + UUID.randomUUID();
        connectorRepository.save(Connector.of(tenantB, codeB, "B", model.getId(),
                SourceType.UPLOAD, "EA"));

        int count = TenantScopedRepository.scoped(jdbcClient, """
                        SELECT count(*)::int FROM connector
                        WHERE tenant_id = :tenantId AND code = :code
                        """)
                .param("code", codeB)
                .query(Integer.class).single();

        assertThat(count).as("기본 테넌트에서는 B 의 커넥터가 세어지면 안 된다").isZero();
    }

    @Test
    @DisplayName("테넌트 조건이 없는 read model 쿼리는 거부한다")
    void 조건_없는_쿼리를_막는다() {
        assertThatThrownBy(() -> TenantScopedRepository.scoped(jdbcClient,
                "SELECT count(*)::int FROM connector"))
                .as("조건을 깜빡한 쿼리를 조용히 통과시키면 다른 테넌트 데이터가 섞인다")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정리하지 않으면 다음 요청이 앞 요청의 테넌트로 조회한다")
    void 컨텍스트를_정리한다() {
        UUID tenantB = newTenant();
        TenantContext.set(tenantB);
        assertThat(TenantContext.current()).isEqualTo(tenantB);

        TenantContext.clear();

        assertThat(TenantContext.current()).isEqualTo(TenantContext.DEFAULT_TENANT_ID);
    }

    /** 두 번째 테넌트. 격리를 증명하려면 실제로 두 개가 있어야 한다. */
    private UUID newTenant() {
        UUID id = UuidV7.generate();
        jdbcClient.sql("""
                        INSERT INTO tenant (id, code, name, created_at, updated_at)
                        VALUES (:id, :code, :name, now(), now())
                        """)
                .param("id", id)
                .param("code", "t-" + id)
                .param("name", "격리 검증용")
                .update();
        return id;
    }
}
