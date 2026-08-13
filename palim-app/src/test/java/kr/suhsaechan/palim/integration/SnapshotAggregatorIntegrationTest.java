package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.reconcile.engine.SnapshotAggregator;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnit;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitMember;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 확정된 정합 단위로 두 원천의 재고를 같은 기준으로 합산한다.
 *
 * <p>여기가 <b>대조의 신뢰가 걸린 자리</b>다. {@code confirmed_at IS NOT NULL} 이 빠지면
 * 사람이 확인하지 않은 추측으로 재고를 합산하게 되고, 그 결과가 맞는지 아무도 모른다.
 * 결과가 «틀렸다» 가 아니라 «맞는 것처럼 보인다» 는 것이 문제다.
 */
class SnapshotAggregatorIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private SnapshotAggregator aggregator;
    @Autowired private ReconcileUnitService unitService;
    @Autowired private JdbcClient jdbcClient;

    private Instant baseAt;
    private String erpSource;
    private String wmsSource;

    @BeforeEach
    void setUp() {
        baseAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        erpSource = "erp-" + UUID.randomUUID().toString().substring(0, 6);
        wmsSource = "wms-" + UUID.randomUUID().toString().substring(0, 6);
    }

    /** 표준 스냅샷 한 줄. 수집이 담았을 모습 그대로 넣는다. */
    private void snapshot(String source, String itemRef, String quantity, String rawName) {
        OffsetDateTime at = baseAt.atOffset(ZoneOffset.UTC);
        jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source, warehouse_code, lot_code,
                             quantity, base_quantity, base_unit, raw_item_name, created_at, updated_at)
                        VALUES (:id, :tenant, :item, :at, :source, '', '',
                                :qty, :qty, 'EA', :rawName, :at, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("item", itemRef)
                .param("at", at)
                .param("source", source)
                .param("qty", new BigDecimal(quantity))
                .param("rawName", rawName)
                .update();
    }

    private String code() {
        return "UNIT-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 전산은 박스로 1개, 물류는 낱개로 12개를 잡는다. 환산 계수를 곱하면 같은 수량이 된다 —
     * 이것이 세트 상품까지 흡수하는 구조다.
     */
    @Test
    @DisplayName("환산 계수를 곱해 단위별로 합산한다")
    void 환산해서_합산한다() {
        ReconcileUnit unit = unitService.create(code(), "제품A 12입", "BOX");
        String erpItem = "ERP-A";
        String wmsItem = "WMS-A";

        unitService.confirm(unitService.propose(
                unit.getId(), erpSource, erpItem, BigDecimal.ONE).getId());
        unitService.confirm(unitService.propose(
                unit.getId(), wmsSource, wmsItem, new BigDecimal("0.5")).getId());

        snapshot(erpSource, erpItem, "10", "제품A 12입");
        snapshot(wmsSource, wmsItem, "24", "제품A 낱개");

        Map<UUID, BigDecimal> erp =
                aggregator.sumByUnit(TENANT, erpSource, baseAt, "base_quantity");
        Map<UUID, BigDecimal> wms =
                aggregator.sumByUnit(TENANT, wmsSource, baseAt, "base_quantity");

        assertThat(erp.get(unit.getId())).isEqualByComparingTo("10");
        assertThat(wms.get(unit.getId()))
                .as("낱개 24개에 0.5 를 곱하면 12박스. 세는 단위가 달라도 같은 기준이 된다")
                .isEqualByComparingTo("12");
    }

    /**
     * 확인하지 않은 연결이 합산에 들어가면 «사람이 안 본 추측» 으로 재고가 계산된다.
     * 그 결과가 맞는지 확인할 방법이 없다.
     */
    @Test
    @DisplayName("확정하지 않은 연결은 합산에서 뺀다")
    void 제안은_합산하지_않는다() {
        ReconcileUnit unit = unitService.create(code(), "제품B", "EA");
        String itemRef = "ERP-B";

        // 제안만 하고 확정하지 않는다
        unitService.propose(unit.getId(), erpSource, itemRef, BigDecimal.ONE);
        snapshot(erpSource, itemRef, "50", "제품B");

        Map<UUID, BigDecimal> sums =
                aggregator.sumByUnit(TENANT, erpSource, baseAt, "base_quantity");

        assertThat(sums)
                .as("확인 안 한 추측으로 합산하면 결과가 맞는지 아무도 모른다")
                .doesNotContainKey(unit.getId());
    }

    /**
     * 어느 단위에도 없는 품목을 «없는 셈» 치면 재고가 있는데도 대조에서 사라진다.
     * 미매칭으로 남겨야 사람이 연결할 것이 있다는 사실을 안다.
     */
    @Test
    @DisplayName("연결되지 않은 품목을 미매칭으로 알린다")
    void 미매칭을_알린다() {
        snapshot(erpSource, "ERP-ORPHAN", "7", "이름 없는 제품");

        var unmatched = aggregator.unmatched(TENANT, erpSource, baseAt, "base_quantity");

        assertThat(unmatched).hasSize(1);
        assertThat(unmatched.getFirst().itemRef()).isEqualTo("ERP-ORPHAN");
        assertThat(unmatched.getFirst().rawName())
                .as("코드만 보여주면 사람이 무엇인지 알아볼 수 없다")
                .isEqualTo("이름 없는 제품");
        assertThat(unmatched.getFirst().quantity()).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("기준 시각이 다른 자료는 섞지 않는다")
    void 다른_기준일은_섞이지_않는다() {
        ReconcileUnit unit = unitService.create(code(), "제품C", "EA");
        String itemRef = "ERP-C";
        unitService.confirm(unitService.propose(
                unit.getId(), erpSource, itemRef, BigDecimal.ONE).getId());
        snapshot(erpSource, itemRef, "5", "제품C");

        Map<UUID, BigDecimal> other = aggregator.sumByUnit(
                TENANT, erpSource, baseAt.minus(1, ChronoUnit.DAYS), "base_quantity");

        assertThat(other)
                .as("다른 시각의 재고를 섞으면 그 사이 출고분만큼 무조건 어긋난다")
                .isEmpty();
    }

    @Test
    @DisplayName("가장 최근 기준 시각을 찾는다")
    void 최근_기준_시각을_찾는다() {
        snapshot(erpSource, "ERP-D", "1", "제품D");

        assertThat(aggregator.latestBaseAt(TENANT, erpSource))
                .hasValueSatisfying(found ->
                        assertThat(found.truncatedTo(ChronoUnit.SECONDS))
                                .isEqualTo(baseAt.truncatedTo(ChronoUnit.SECONDS)));
    }
}
