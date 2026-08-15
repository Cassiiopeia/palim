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
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.engine.ReconcileEngine;
import kr.suhsaechan.palim.reconcile.run.DiffState;
import kr.suhsaechan.palim.reconcile.run.DiffType;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiff;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiffRepository;
import kr.suhsaechan.palim.reconcile.run.ReconcileRun;
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
 * 두 원천의 재고를 견주어 <b>시간 탓으로 설명되지 않는 차이만</b> 남긴다.
 *
 * <p>전산에 입력은 됐는데 물류에 아직 안 잡힌 경우가 실무에서 가장 흔하고, 그런 것은 다음
 * 회차에 저절로 사라진다. 첫 회차부터 알리면 매일 헛알림이 가고, 그러면 진짜 알림도 안 보게
 * 된다 — 알림이 잡음이 되는 순간 그 알림은 없는 것과 같아진다.
 */
class ReconcileEngineIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private ReconcileEngine engine;
    @Autowired private ReconcileUnitService unitService;
    @Autowired private ReconcileDefinitionRepository definitions;
    @Autowired private ReconcileDiffRepository diffs;
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
    void clearTenant() {
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

    private ReconcileDefinition definition(String tolerance) {
        return definitions.save(ReconcileDefinition.of(TENANT,
                "DEF-" + UUID.randomUUID().toString().substring(0, 8), "전산 대 물류",
                erp, wms, "base_quantity", new BigDecimal(tolerance), null));
    }

    /** 양쪽에 같은 정합 단위로 묶인 품목을 하나 만든다. */
    private ReconcileUnit linkedUnit(String erpQty, String wmsQty) {
        ReconcileUnit unit = unitService.create(
                "UNIT-" + UUID.randomUUID().toString().substring(0, 8), "제품A", "EA");
        String erpItem = "E-" + UUID.randomUUID().toString().substring(0, 6);
        String wmsItem = "W-" + UUID.randomUUID().toString().substring(0, 6);

        unitService.confirm(unitService.propose(
                unit.getId(), erp, erpItem, BigDecimal.ONE).getId());
        unitService.confirm(unitService.propose(
                unit.getId(), wms, wmsItem, BigDecimal.ONE).getId());

        snapshot(erp, erpItem, erpQty, baseAt);
        snapshot(wms, wmsItem, wmsQty, baseAt);
        return unit;
    }

    private List<ReconcileDiff> diffsOf(ReconcileRun run) {
        return diffs.findByRunIdOrderByStateAscUnitCodeAsc(run.getId());
    }

    /**
     * 소수점 반올림이나 낱개 한두 개까지 전부 띄우면 목록이 잡음으로 차서 진짜 문제가 묻힌다.
     */
    @Test
    @DisplayName("허용 오차 이내는 차이로 남기지 않는다")
    void 허용_오차_이내는_넘어간다() {
        linkedUnit("100", "98");
        ReconcileDefinition definition = definition("5");

        ReconcileRun run = engine.run(definition.getId());

        assertThat(diffsOf(run))
                .as("2 는 허용 오차 5 안이라 볼 것이 없다")
                .noneMatch(d -> d.getDiffType() == DiffType.LEFT_MORE);
    }

    @Test
    @DisplayName("허용 오차를 넘으면 차이로 남긴다")
    void 오차를_넘으면_기록한다() {
        linkedUnit("100", "80");
        ReconcileDefinition definition = definition("5");

        ReconcileRun run = engine.run(definition.getId());

        assertThat(diffsOf(run))
                .filteredOn(d -> d.getDiffType() == DiffType.LEFT_MORE)
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.getDelta()).isEqualByComparingTo("20");
                    assertThat(d.getLeftQuantity()).isEqualByComparingTo("100");
                    assertThat(d.getRightQuantity()).isEqualByComparingTo("80");
                });
    }

    /**
     * 처음 보이는 차이는 반영 지연일 수 있다. 전산에 입력은 됐는데 물류에 아직 안 잡힌 경우가
     * 가장 흔하고, 그런 것은 다음 회차에 저절로 사라진다.
     */
    @Test
    @DisplayName("처음 본 차이는 관찰중으로 둔다")
    void 처음_본_차이는_관찰중이다() {
        linkedUnit("100", "80");
        ReconcileDefinition definition = definition("0");

        ReconcileRun run = engine.run(definition.getId());

        assertThat(diffsOf(run))
                .filteredOn(d -> d.getDiffType() == DiffType.LEFT_MORE)
                .singleElement()
                .satisfies(d -> assertThat(d.getState())
                        .as("첫 회차부터 알리면 매일 헛알림이 간다")
                        .isEqualTo(DiffState.OBSERVING));
    }

    /**
     * 두 번째에도 같은 방향으로 남으면 시간으로 설명되지 않는다. 그때 알린다.
     */
    @Test
    @DisplayName("다음 실행에도 같은 방향이면 확정으로 올린다")
    void 두_번_보이면_확정한다() {
        linkedUnit("100", "80");
        ReconcileDefinition definition = definition("0");

        engine.run(definition.getId());
        ReconcileRun second = engine.run(definition.getId());

        assertThat(diffsOf(second))
                .filteredOn(d -> d.getDiffType() == DiffType.LEFT_MORE)
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.getState())
                            .as("시간으로 설명되지 않는 차이만 사람에게 알린다")
                            .isEqualTo(DiffState.CONFIRMED);
                    assertThat(d.getFirstSeenRunId())
                            .as("처음 관찰된 실행을 물려받아야 며칠째인지 셀 수 있다")
                            .isNotEqualTo(second.getId());
                });
    }

    /**
     * 매칭 안 된 품목 하나 때문에 대조 전체를 중단하면 나머지 결과도 못 보게 되고,
     * 사람이 매칭을 끝낼 때까지 대조를 아예 쓸 수 없다.
     */
    @Test
    @DisplayName("어느 단위에도 없는 품목은 미매칭으로 남기고 대조는 계속한다")
    void 미매칭이_있어도_대조는_돈다() {
        linkedUnit("100", "100");
        snapshot(erp, "ORPHAN-" + UUID.randomUUID().toString().substring(0, 6), "7", baseAt);
        ReconcileDefinition definition = definition("0");

        ReconcileRun run = engine.run(definition.getId());

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCESS);
        assertThat(diffsOf(run))
                .as("연결할 것이 있다는 사실을 사람이 알아야 한다")
                .anyMatch(d -> d.getDiffType() == DiffType.UNMATCHED_LEFT);
        assertThat(run.getUnmatchedCount()).isGreaterThanOrEqualTo(1);
    }

    /**
     * 기준 시각이 어긋난 것을 조용히 넘어가면 며칠째 대조가 안 되고 있다는 사실을 아무도
     * 모른다. 실패도 기록이어야 「어제는 왜 안 돌았나」 에 답할 수 있다.
     */
    @Test
    @DisplayName("기준 시각이 다르면 실패로 남기고 사유를 적는다")
    void 시각이_어긋나면_실패로_남긴다() {
        String erpItem = "E-" + UUID.randomUUID().toString().substring(0, 6);
        String wmsItem = "W-" + UUID.randomUUID().toString().substring(0, 6);
        snapshot(erp, erpItem, "10", baseAt);
        snapshot(wms, wmsItem, "10", baseAt.minus(1, ChronoUnit.DAYS));

        ReconcileRun run = engine.run(definition("0").getId());

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getMessage()).isNotBlank();
    }

    /**
     * 실패 사유가 <b>사람 말</b>인가.
     *
     * <p>사장님이 대조를 눌렀을 때 화면에 이런 것이 떴다.
     *
     * <pre>RECONCILE_SNAPSHOT_MISSING(R002) args=[ecount-stock]</pre>
     *
     * <p>이건 로그용 문자열이다. 「R002 가 뭐지」 를 찾으러 화면을 떠나야 하고, 결국 서버
     * 로그를 뒤져야 원인을 알 수 있었다. <b>사람 말은 이미 준비돼 있었는데 쓰이지 않고
     * 있었다</b> — 연동 화면은 이 규칙을 지키는데 대조 화면만 빠져 있었다.
     */
    @Test
    @DisplayName("실패 사유를 코드가 아니라 사람 말로 남긴다")
    void 사유를_사람_말로_남긴다() {
        // 한쪽에만 자료가 있는 상태 — 담기를 안 했을 때 사장님이 겪는 그 상황이다
        snapshot(erp, "E-" + UUID.randomUUID().toString().substring(0, 6), "10", baseAt);

        // 언어를 일부러 정하지 않는다 — 매일 자동으로 도는 대조가 바로 이 상태다.
        //
        // 예전에는 여기서 서버 기본값(영어)이 나와, 사람이 누른 것은 한글이고 자동으로 돈
        // 것은 영문이라 같은 목록에 두 언어가 섞였다. 화면이 전부 한국어인 제품에서.
        ReconcileRun run = engine.run(definition("0").getId());

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getMessage())
                .as("코드 이름이 그대로 나가면 무엇을 해야 하는지 알 수 없다")
                .doesNotContain("R002")
                .doesNotContain("args=")
                .doesNotContain("RECONCILE_SNAPSHOT_MISSING")
                .as("자동으로 돈 것도 화면과 같은 말이어야 한다")
                .contains("비교할 재고가 없습니다");
    }
}
