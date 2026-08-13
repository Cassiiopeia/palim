package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.engine.ReconcileScheduler;
import kr.suhsaechan.palim.reconcile.run.ReconcileRunRepository;
import kr.suhsaechan.palim.reconcile.run.RunStatus;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnit;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 매일 스스로 맞춰 본다.
 *
 * <p>사람이 화면에 들어와 버튼을 눌러야 돌아가는 구조라면 바쁜 날 거르고, 그러다 안 하게 된다.
 * 수집까지 자동으로 만들어 두고 대조만 수동으로 남기면 <b>절반만 자동인 셈</b>이라 결국 사람
 * 손을 탄다.
 */
class ReconcileSchedulerIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private ReconcileScheduler scheduler;
    @Autowired private ReconcileUnitService unitService;
    @Autowired private ReconcileDefinitionRepository definitions;
    @Autowired private ReconcileRunRepository runs;
    @Autowired private JdbcClient jdbcClient;

    private Instant baseAt;
    private String erp;
    private String wms;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        baseAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        erp = "erp-" + UUID.randomUUID().toString().substring(0, 6);
        wms = "wms-" + UUID.randomUUID().toString().substring(0, 6);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void snapshot(String source, String itemRef, String qty, Instant at) {
        jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source, warehouse_code, lot_code,
                             quantity, base_quantity, base_unit, raw_item_name, created_at, updated_at)
                        VALUES (:id, :tenant, :item, :at, :source, '', '',
                                :qty, :qty, 'EA', :name, :at, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("item", itemRef)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("source", source)
                .param("qty", new BigDecimal(qty))
                .param("name", "제품 " + itemRef)
                .update();
    }

    private ReconcileDefinition linkedDefinition(String erpQty, String wmsQty, Instant wmsAt) {
        ReconcileUnit unit = unitService.create(
                "UNIT-" + UUID.randomUUID().toString().substring(0, 8), "제품A", "EA");
        String erpItem = "E-" + UUID.randomUUID().toString().substring(0, 6);
        String wmsItem = "W-" + UUID.randomUUID().toString().substring(0, 6);
        unitService.confirm(unitService.propose(
                unit.getId(), erp, erpItem, BigDecimal.ONE).getId());
        unitService.confirm(unitService.propose(
                unit.getId(), wms, wmsItem, BigDecimal.ONE).getId());

        snapshot(erp, erpItem, erpQty, baseAt);
        snapshot(wms, wmsItem, wmsQty, wmsAt);

        return definitions.save(ReconcileDefinition.of(TENANT,
                "DEF-" + UUID.randomUUID().toString().substring(0, 8), "전산 대 물류",
                erp, wms, "base_quantity", BigDecimal.ZERO, new BigDecimal("10")));
    }

    @Test
    @DisplayName("사람이 누르지 않아도 스스로 맞춰 본다")
    void 자동으로_맞춰_본다() {
        ReconcileDefinition definition = linkedDefinition("100", "80", baseAt);

        scheduler.runAll();

        assertThat(runs.findByDefinitionIdOrderByStartedAtDesc(definition.getId()))
                .as("매일 도는 일이 되어야 연동이 업무가 된다")
                .isNotEmpty()
                .allSatisfy(run -> assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCESS));
    }

    /**
     * 수집이 아직 안 끝나 한쪽 기준 시각이 어긋나는 날이 있다. 그때는 <b>건너뛰고 다음 회차에
     * 다시</b> 한다 — 억지로 비교하면 매일 어긋난 결과가 쌓이고, 그러면 아무도 그 화면을 보지
     * 않게 된다.
     */
    @Test
    @DisplayName("기준 시각이 어긋난 날은 실패로 남기고 넘어간다")
    void 시각이_어긋나면_건너뛴다() {
        ReconcileDefinition definition =
                linkedDefinition("100", "80", baseAt.minus(1, ChronoUnit.DAYS));

        scheduler.runAll();

        assertThat(runs.findByDefinitionIdOrderByStartedAtDesc(definition.getId()))
                .as("건너뛴 것도 기록이어야 「어제는 왜 안 돌았나」 에 답할 수 있다")
                .isNotEmpty()
                .allSatisfy(run -> {
                    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
                    assertThat(run.getMessage()).isNotBlank();
                });
    }

    /**
     * 한 대조가 막혔다고 다른 대조까지 멈추면, 볼 수 있었을 문제도 못 본다.
     */
    @Test
    @DisplayName("하나가 실패해도 나머지는 돈다")
    void 하나가_막혀도_나머지는_돈다() {
        ReconcileDefinition broken =
                linkedDefinition("100", "80", baseAt.minus(2, ChronoUnit.DAYS));

        String erp2 = "erp2-" + UUID.randomUUID().toString().substring(0, 6);
        String wms2 = "wms2-" + UUID.randomUUID().toString().substring(0, 6);
        ReconcileUnit unit = unitService.create(
                "UNIT-" + UUID.randomUUID().toString().substring(0, 8), "제품B", "EA");
        unitService.confirm(unitService.propose(
                unit.getId(), erp2, "E2", BigDecimal.ONE).getId());
        unitService.confirm(unitService.propose(
                unit.getId(), wms2, "W2", BigDecimal.ONE).getId());
        snapshot(erp2, "E2", "50", baseAt);
        snapshot(wms2, "W2", "50", baseAt);
        ReconcileDefinition healthy = definitions.save(ReconcileDefinition.of(TENANT,
                "DEF-" + UUID.randomUUID().toString().substring(0, 8), "다른 대조",
                erp2, wms2, "base_quantity", BigDecimal.ZERO, null));

        scheduler.runAll();

        assertThat(runs.findByDefinitionIdOrderByStartedAtDesc(healthy.getId()))
                .as("한 대조가 막혔다고 다른 것까지 멈추면 볼 수 있었을 문제도 못 본다")
                .isNotEmpty()
                .allSatisfy(run -> assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCESS));
        assertThat(runs.findByDefinitionIdOrderByStartedAtDesc(broken.getId())).isNotEmpty();
    }
}
