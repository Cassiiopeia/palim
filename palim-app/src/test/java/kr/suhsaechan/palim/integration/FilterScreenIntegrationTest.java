package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import kr.suhsaechan.palim.reconcile.filter.FilterOperator;
import kr.suhsaechan.palim.reconcile.filter.FilterRow;
import kr.suhsaechan.palim.reconcile.filter.FilterRowRepository;
import kr.suhsaechan.palim.reconcile.filter.FilterSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 조건이 <b>화면에 보이고, 거기서 고치러 갈 수 있는가</b>.
 *
 * <p>이것이 없어서 실제로 막혔다 — 조건은 뒤에서 제대로 걸리는데 품목 묶기 화면이 말하지 않아,
 * 보고 있는 수치가 전 창고 합인지 한 창고인지 알 수 없었다. 그리고 그 화면에서 대조 정의로 가는
 * 링크가 없어 조건을 고치러 갈 수도 없었다.
 *
 * <p>템플릿 표현식 오류는 컴파일에 걸리지 않는다. 화면을 여는 순간 터지고, 그때는 이미 배포된
 * 뒤다.
 */
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class FilterScreenIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ReconcileDefinitionRepository definitions;
    @Autowired private FilterRowRepository filterRows;
    @Autowired private JdbcClient jdbcClient;

    private Instant baseAt;
    private String left;
    private String right;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        baseAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        left = "l-" + UUID.randomUUID().toString().substring(0, 6);
        right = "r-" + UUID.randomUUID().toString().substring(0, 6);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("대조 정의 화면이 담긴 값을 수량과 함께 그린다")
    void rendersValueOptions() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "9426", "01");
        snapshot(left, "B", "312", "02");

        mockMvc.perform(get("/reconcile/" + definition.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("볼 조건")))
                .andExpect(content().string(containsString("9,426")))
                .andExpect(content().string(containsString("창고")));
    }

    @Test
    @DisplayName("걸 수 있는 칸에 창고가 아닌 것도 나온다 — 이것이 이 작업의 목적이다")
    void rendersNonWarehouseFields() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "10", "01");

        mockMvc.perform(get("/reconcile/" + definition.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("품질상태")))
                .andExpect(content().string(containsString("유통기한")));
    }

    @Test
    @DisplayName("조건을 안 걸었는데 창고가 여럿이면 경고가 뜬다")
    void warnsWhenNothingChosen() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "10", "01");
        snapshot(left, "B", "20", "02");

        mockMvc.perform(get("/reconcile/" + definition.getId()))
                .andExpect(content().string(containsString("전부 더해서")));
    }

    @Test
    @DisplayName("품목 묶기 화면이 지금 걸린 조건을 말하고 고치러 갈 길을 낸다")
    void unitsScreenShowsFiltersAndLinks() throws Exception {
        ReconcileDefinition definition = definition();
        filterRows.save(FilterRow.field(TENANT, definition.getId(), FilterSide.LEFT, 0,
                "warehouse_code", FilterOperator.IN, List.of("01")));
        snapshot(left, "A", "10", "01");
        snapshot(right, "B", "10", "");

        mockMvc.perform(get("/reconcile/units")
                        .param("definitionId", definition.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("볼 조건")))
                .andExpect(content().string(containsString("01")))
                .andExpect(content().string(containsString(
                        "/reconcile/" + definition.getId() + "#filters")));
    }

    @Test
    @DisplayName("조건이 없으면 품목 묶기 화면이 「전부 더해서 봅니다」 라고 말한다")
    void unitsScreenSaysWhenNoFilter() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "10", "01");
        snapshot(right, "B", "10", "");

        mockMvc.perform(get("/reconcile/units")
                        .param("definitionId", definition.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("전부 더해서")));
    }

    @Test
    @DisplayName("조건 줄이 저장되고 다시 읽힌다")
    void savesRows() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "10", "01");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/reconcile/" + definition.getId() + "/filters")
                        .param("side", "LEFT")
                        .param("fieldKey", "warehouse_code", "quality_status")
                        .param("operator", "IN", "NOT_IN")
                        .param("values", "01|02", "불량")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());

        var saved = filterRows.findByDefinitionIdOrderBySideAscOrdinalAsc(definition.getId());
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getValues()).containsExactly("01", "02");
        assertThat(saved.get(1).getValues()).containsExactly("불량");
    }

    @Test
    @DisplayName("읽을 수 없는 식은 저장이 막힌다 — 도는 순간까지 미루지 않는다")
    void rejectsBadExpression() throws Exception {
        ReconcileDefinition definition = definition();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/reconcile/" + definition.getId() + "/filters")
                        .param("side", "LEFT")
                        .param("expression", "창고 = '01'; DROP TABLE std_stock_snapshot")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(filterRows.findByDefinitionIdOrderBySideAscOrdinalAsc(definition.getId()))
                .isEmpty();
    }

    private ReconcileDefinition definition() {
        return definitions.save(ReconcileDefinition.of(TENANT,
                "DEF-" + UUID.randomUUID().toString().substring(0, 8), "시험 대조",
                left, right, "base_quantity", BigDecimal.ZERO, null));
    }

    private void snapshot(String source, String itemRef, String qty, String warehouse) {
        jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source, warehouse_code, lot_code,
                             quantity, base_quantity, base_unit, raw_item_name,
                             created_at, updated_at)
                        VALUES (:id, :tenant, :item, :at, :source, :warehouse, '',
                                :qty, :qty, 'EA', :name, :at, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("item", itemRef)
                .param("at", baseAt.atOffset(ZoneOffset.UTC))
                .param("source", source)
                .param("warehouse", warehouse)
                .param("qty", new BigDecimal(qty))
                .param("name", "품목 " + itemRef)
                .update();
    }
}
