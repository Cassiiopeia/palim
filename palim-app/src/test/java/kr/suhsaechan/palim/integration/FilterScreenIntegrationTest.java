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

    /**
     * 이 화면의 조건 편집이 <b>실제로 눌리는가</b>.
     *
     * <p>줄을 더하고 지우는 코드가 화면 안에 박혀 있었다. CSP 가 {@code script-src 'self'} 라
     * 브라우저가 그 코드를 한 줄도 실행하지 않는데, 화면은 200 으로 멀쩡히 열리고 버튼도
     * 보인다 — 눌러도 아무 일이 없을 뿐이다. 그동안 운영 대조는 조건을 걸지 못해 전 창고를
     * 더해 견뎠고, 맞는 품목까지 틀린 것으로 보였다.
     *
     * <p>{@code noInlineCode()} 가 정확히 이것을 잡으라고 있는데 이 화면 시험이 부르지 않아
     * 통과했다. 이제 부른다.
     */
    @Test
    @DisplayName("화면에 박힌 코드가 없다 — 있으면 조건을 걸 수 없다")
    void hasNoInlineCode() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "10", "01");

        mockMvc.perform(get("/reconcile/" + definition.getId()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.noInlineCode())
                .andExpect(RenderAssertions.fullyRendered());
    }

    /**
     * 코드가 죽어도 조건 하나는 걸 수 있어야 한다.
     *
     * <p>줄을 더하는 것은 코드가 하는 일이고, 코드는 죽을 수 있다. 실제로 죽어 있었는데 그때
     * 걸린 조건이 없으면 줄이 <b>하나도</b> 없어 조건을 걸 방법 자체가 없었다.
     */
    @Test
    @DisplayName("걸린 조건이 없어도 빈 줄이 하나 떠 있다")
    void showsOneRowWhenEmpty() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "10", "01");

        mockMvc.perform(get("/reconcile/" + definition.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-row")))
                .andExpect(content().string(containsString("name=\"values\"")));
    }

    /**
     * 담긴 값을 <b>골라서</b> 걸 수 있어야 한다.
     *
     * <p>목록만 보여주면 창고 코드를 조건 칸에 손으로 옮겨 적어야 한다. 옮겨 적는 동안 틀리고,
     * 틀리면 조건이 아무것도 거르지 않는데 화면은 「걸려 있다」 고 말한다.
     */
    @Test
    @DisplayName("담긴 값을 체크로 고를 수 있다")
    void valuesArePickable() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "9426", "01");
        snapshot(left, "B", "312", "02");

        mockMvc.perform(get("/reconcile/" + definition.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-pick-field=\"warehouse_code\"")))
                .andExpect(content().string(containsString("data-pick-value=\"01\"")));
    }

    /**
     * 아무것도 안 적은 줄이 저장을 막지 않는다.
     *
     * <p>화면이 빈 줄을 늘 띄우므로 조건을 하나도 안 걸고 저장하는 일이 흔하다. 그것을 조건으로
     * 만들면 값 없는 {@code IN} 이 되어 저장 전체가 거부된다 — <b>지우려던 사람이 지우지도
     * 못한다.</b>
     */
    @Test
    @DisplayName("빈 줄만 보내면 조건이 지워진다 — 저장이 막히지 않는다")
    void emptyRowClearsInsteadOfFailing() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "10", "01");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/reconcile/" + definition.getId() + "/filters")
                        .param("side", "LEFT")
                        .param("fieldKey", "warehouse_code")
                        .param("operator", "IN")
                        .param("values", "")
                        .param("expression", "")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                // 「거부돼서 안 남은 것」 과 「지워져서 안 남은 것」 은 결과가 같다. 어느 쪽인지
                // 가르지 않으면 저장이 막혀도 이 시험은 통과한다.
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attributeExists("flashSuccess"));

        assertThat(filterRows.findByDefinitionIdOrderBySideAscOrdinalAsc(definition.getId()))
                .as("빈 줄은 조건이 아니라 빈 자리다")
                .isEmpty();

        mockMvc.perform(get("/reconcile/" + definition.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("전부 더해서")));
    }

    /**
     * 값 안의 콤마가 값을 쪼개지 않는다.
     *
     * <p>{@code @RequestParam List<String>} 으로 받으면 Spring 이 값을 <b>콤마로 나눈다.</b>
     * 창고명·품질상태처럼 사람이 붙인 이름에는 콤마가 들어갈 수 있고, 그러면 조건이 조용히 두
     * 값이 되어 <b>다른 뜻</b>이 된다 — 걸어 둔 사람은 그 사실을 알 길이 없다.
     *
     * <p>값을 잇는 글자는 세로줄 하나뿐이다({@code FilterValues.DELIMITER}).
     */
    @Test
    @DisplayName("값에 콤마가 있어도 쪼개지지 않는다")
    void commaInValueSurvives() throws Exception {
        ReconcileDefinition definition = definition();
        snapshot(left, "A", "10", "01");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/reconcile/" + definition.getId() + "/filters")
                        .param("side", "LEFT")
                        .param("fieldKey", "warehouse_name")
                        .param("operator", "IN")
                        .param("values", "사무실, 창고")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());

        var saved = filterRows.findByDefinitionIdOrderBySideAscOrdinalAsc(definition.getId());
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getValues())
                .as("콤마로 쪼개지면 조건이 다른 뜻이 된다")
                .containsExactly("사무실, 창고");
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
