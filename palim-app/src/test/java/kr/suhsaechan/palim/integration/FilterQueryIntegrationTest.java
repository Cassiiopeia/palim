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
import kr.suhsaechan.palim.reconcile.define.Pairing;
import kr.suhsaechan.palim.reconcile.engine.SnapshotAggregator;
import kr.suhsaechan.palim.reconcile.filter.FieldCatalog;
import kr.suhsaechan.palim.reconcile.filter.FilterNode;
import kr.suhsaechan.palim.reconcile.filter.FilterOperator;
import kr.suhsaechan.palim.reconcile.filter.FilterSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 조건이 <b>실제 SQL 로 걸리는가</b>.
 *
 * <p>창고 하나만 고를 수 있던 것을 어느 칸으로든 걸 수 있게 넓혔다. 이 시험이 지키는 것은
 * 「창고가 아닌 칸으로도 걸린다」 와 「조건이 없으면 지금까지와 같은 답이 나온다」 둘이다.
 *
 * <p>후자가 없으면 조건을 안 건 대조가 조용히 달라지는데, 그것은 아무도 눈치채지 못한다.
 */
class FilterQueryIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private SnapshotAggregator aggregator;
    @Autowired private JdbcClient jdbcClient;

    private Instant baseAt;
    private String source;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        baseAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        source = "src-" + UUID.randomUUID().toString().substring(0, 6);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static FilterSpec spec(String key, FilterOperator op, String... values) {
        return new FilterSpec(new FilterNode.Compare(
                FieldCatalog.find(key).orElseThrow(), op, List.of(values)));
    }

    @Test
    @DisplayName("창고 조건이 미매칭 조회에 걸린다")
    void filtersUnmatched() {
        snapshot("A", "100", "01", "정상", "{}");
        snapshot("B", "200", "02", "정상", "{}");

        var all = aggregator.unmatched(TENANT, source, baseAt, "base_quantity",
                FilterSpec.all());
        var onlyFirst = aggregator.unmatched(TENANT, source, baseAt, "base_quantity",
                spec("warehouse_code", FilterOperator.IN, "01"));

        assertThat(all).hasSize(2);
        assertThat(onlyFirst).hasSize(1);
        assertThat(onlyFirst.get(0).quantity()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("창고가 아닌 칸으로도 거를 수 있다 — 이것이 이 작업의 목적이다")
    void filtersByNonWarehouseField() {
        snapshot("A", "100", "01", "정상", "{}");
        snapshot("B", "200", "01", "불량", "{}");

        var normalOnly = aggregator.unmatched(TENANT, source, baseAt, "base_quantity",
                spec("quality_status", FilterOperator.NOT_IN, "불량"));

        assertThat(normalOnly).hasSize(1);
        assertThat(normalOnly.get(0).quantity()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("원천 고유 칸(attributes)으로도 거를 수 있다 — 계정이 바뀌어도 화면이 돈다")
    void filtersByAttribute() {
        snapshot("A", "100", "", "정상", "{\"재고구분\": \"정상\"}");
        snapshot("B", "200", "", "정상", "{\"재고구분\": \"보류\"}");

        var held = aggregator.unmatched(TENANT, source, baseAt, "base_quantity",
                spec("attributes.재고구분", FilterOperator.IN, "보류"));

        assertThat(held).hasSize(1);
        assertThat(held.get(0).quantity()).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("조건이 없으면 지금까지와 같은 답이 나온다")
    void noFilterMeansEverything() {
        snapshot("A", "100", "01", "정상", "{}");
        snapshot("B", "200", "02", "불량", "{}");

        assertThat(aggregator.unmatched(TENANT, source, baseAt, "base_quantity",
                FilterSpec.all())).hasSize(2);
    }

    @Test
    @DisplayName("한쪽 원천만 걸어도 다른 쪽이 안 물든다")
    void sidesStayApart() {
        Pairing pairing = new Pairing("left-src", "right-src",
                spec("warehouse_code", FilterOperator.IN, "01"),
                FilterSpec.all(), "base_quantity");

        assertThat(pairing.filterOf("left-src").isAll()).isFalse();
        assertThat(pairing.filterOf("right-src").isAll()).isTrue();
    }

    @Test
    @DisplayName("어느 칸이든 값 후보를 수량과 함께 준다")
    void listsValuesOfAnyField() {
        snapshot("A", "900", "01", "정상", "{}");
        snapshot("B", "100", "02", "정상", "{}");
        snapshot("C", "50", "02", "불량", "{}");

        var warehouses = aggregator.valuesOf(TENANT, source,
                FieldCatalog.find("warehouse_code").orElseThrow());
        var qualities = aggregator.valuesOf(TENANT, source,
                FieldCatalog.find("quality_status").orElseThrow());

        // 규모가 큰 것이 앞에 온다 — 맡긴 창고를 규모로 알아본다.
        assertThat(warehouses).extracting(SnapshotAggregator.FieldValue::value)
                .containsExactly("01", "02");
        assertThat(warehouses.get(0).qty()).isEqualByComparingTo("900");
        assertThat(qualities).extracting(SnapshotAggregator.FieldValue::value)
                .containsExactlyInAnyOrder("정상", "불량");
    }

    @Test
    @DisplayName("원천 고유 칸의 이름을 담긴 자료에서 찾는다 — 계정이 바뀌어도 화면이 돈다")
    void findsAttributeKeys() {
        snapshot("A", "10", "01", "정상", "{\"재고구분\": \"정상\", \"입고차수\": \"3\"}");

        assertThat(aggregator.attributeKeys(TENANT, source))
                .containsExactlyInAnyOrder("재고구분", "입고차수");
    }

    @Test
    @DisplayName("조건을 걸면 몇 줄이 남는지 저장 전에 알려준다")
    void previewsBeforeSaving() {
        snapshot("A", "900", "01", "정상", "{}");
        snapshot("B", "100", "02", "정상", "{}");

        var all = aggregator.preview(TENANT, source, FilterSpec.all(), baseAt);
        var narrowed = aggregator.preview(TENANT, source,
                spec("warehouse_code", FilterOperator.IN, "01"), baseAt);

        assertThat(all.totalItems()).isEqualTo(2);
        assertThat(all.keptItems()).isEqualTo(2);
        assertThat(narrowed.totalItems()).isEqualTo(2);
        assertThat(narrowed.keptItems()).isEqualTo(1);
        assertThat(narrowed.keptQty()).isEqualByComparingTo("900");
    }

    @Test
    @DisplayName("값이 없는 칸은 빈 목록이다 — 「담긴 자료가 없습니다」 를 화면이 말한다")
    void emptyWhenNothingLoaded() {
        assertThat(aggregator.valuesOf(TENANT, source,
                FieldCatalog.find("lot_code").orElseThrow())).isEmpty();
    }

    private void snapshot(String itemRef, String qty, String warehouse, String quality,
                          String attributesJson) {
        jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source, warehouse_code,
                             warehouse_name, lot_code, quality_status, quantity, base_quantity,
                             base_unit, raw_item_name, attributes, created_at, updated_at)
                        VALUES (:id, :tenant, :item, :at, :source, :warehouse, :whName, '',
                                :quality, :qty, :qty, 'EA', :name, cast(:attrs as jsonb),
                                :at, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("item", itemRef)
                .param("at", baseAt.atOffset(ZoneOffset.UTC))
                .param("source", source)
                .param("warehouse", warehouse)
                .param("whName", warehouse.isEmpty() ? "" : "창고" + warehouse)
                .param("quality", quality)
                .param("qty", new BigDecimal(qty))
                .param("name", "품목 " + itemRef)
                .param("attrs", attributesJson)
                .update();
    }
}
