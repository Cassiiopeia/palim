package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.notification.NotificationOutbox;
import kr.suhsaechan.palim.notification.NotificationOutboxRepository;
import kr.suhsaechan.palim.notification.NotificationType;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.notification.payload.ReconcileMismatchPayload;
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
    @Autowired private NotificationOutboxRepository outbox;
    @Autowired private OutboxService outboxService;

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

    /**
     * <b>찾은 차이가 실제로 나가는가.</b>
     *
     * <p>이 시험이 없으면 알림 경로가 통째로 죽어도 전 시험이 초록으로 남는다. 실제로 그랬다 —
     * 대기열에 넣는 쪽이 {@code propagation = MANDATORY} 인데 스케줄러는 트랜잭션 밖이라
     * 예외가 났고, 그 예외를 {@code runAll} 의 catch 가 「자동 대조 실패」 로 삼켰다. 회차는
     * 엔진이 자기 트랜잭션에서 이미 SUCCESS 로 커밋한 뒤라 <b>실행 상태를 보는 시험도 통과</b>
     * 했다. 차이를 찾아 놓고도 아무에게도 못 알리는 상태가 그렇게 숨어 있었다.
     *
     * <p>그래서 회차 상태가 아니라 <b>대기열에 실제로 들어갔는지</b>를 본다.
     *
     * <p>두 번 도는 이유는 승격 때문이다. 차이는 한 번 봤다고 확정되지 않는다 — 같은 차이가
     * 두 회차 연속 나와야 확정되고, 확정된 것만 알린다.
     */
    @Test
    @DisplayName("찾은 차이가 알림 대기열에 실제로 들어간다")
    void 차이가_알림으로_나간다() {
        ReconcileDefinition definition = linkedDefinition("100", "80", baseAt);

        scheduler.runAll();
        scheduler.runAll();

        List<NotificationOutbox> sent = outbox.findAll().stream()
                .filter(row -> row.getType() == NotificationType.RECONCILE_MISMATCH)
                .filter(row -> row.getDedupeKey() != null)
                .filter(row -> row.getDedupeKey().contains(definition.getCode()))
                .toList();

        assertThat(sent)
                .as("차이를 찾아 놓고 못 알리면 대조가 있으나 마나다")
                .hasSize(1);

        // 받는 쪽이 읽는 모양 그대로 되읽는다. 한때 다른 종류의 형식으로 넣어 빈 값이 나갔다.
        ReconcileMismatchPayload payload =
                outboxService.readPayload(sent.getFirst(), ReconcileMismatchPayload.class);
        assertThat(payload.definition()).isEqualTo(definition.getName());
        assertThat(payload.leftSource()).isEqualTo(erp);
        assertThat(payload.rightSource()).isEqualTo(wms);
        assertThat(payload.count()).isPositive();
        assertThat(payload.samples()).isNotEmpty();
        assertThat(payload.baseAt())
                .as("시각은 Instant 로 담아야 표시 직전에 지역 시각으로 바뀐다")
                .isNotNull();
    }
}
