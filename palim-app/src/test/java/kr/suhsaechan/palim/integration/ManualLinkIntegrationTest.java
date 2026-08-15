package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import kr.suhsaechan.palim.reconcile.engine.SnapshotAggregator;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitMemberRepository;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

/**
 * <b>이름이 달라도 이을 수 있는가.</b>
 *
 * <p>여기가 이 화면의 존재 이유다. 자동 후보는 이름이 닮은 것만 묶는데, 실제로 안 이어지는
 * 품목은 <b>정확히 이름이 다른 것들</b>이다. 그런데 손으로 고르는 자리가 없어서, 사람이
 * 「저 둘이 같은 거야」 라고 알고 있어도 화면에 그 품목이 나타나지조차 않았다. 막다른 길이었다.
 *
 * <p>담기·검색·쪽 넘김이 <b>한 폼</b> 안에 있는 것도 이 시험이 지킨다. 화면 안에 코드를 넣을
 * 수 없고(CSP), GET 폼은 제출할 때 기존 쿼리 문자열을 버리므로, 담아 둔 것을 지키는 길이
 * 이것뿐이다.
 */
@AutoConfigureMockMvc
class ManualLinkIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ReconcileDefinitionRepository definitions;
    @Autowired private ReconcileUnitMemberRepository members;
    @Autowired private ReconcileUnitService unitService;
    @Autowired private SnapshotAggregator aggregator;
    @Autowired private JdbcClient jdbcClient;

    private ReconcileDefinition definition;
    private String erp;
    private String wms;
    private Instant baseAt;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        baseAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        erp = "erp-" + UUID.randomUUID().toString().substring(0, 6);
        wms = "wms-" + UUID.randomUUID().toString().substring(0, 6);

        // 이름이 서로 다르다 — 자동 후보로는 절대 안 묶인다. 그것이 이 시험의 전제다.
        snapshot(erp, "A0001", "클래식 227", "100");
        snapshot(wms, "SKU-77", "CLASSIC 227g", "8");

        definitions.findByIsActiveTrueOrderByCode().forEach(existing -> {
            existing.deactivate();
            definitions.save(existing);
        });
        definition = definitions.save(ReconcileDefinition.of(TENANT,
                "def-" + UUID.randomUUID().toString().substring(0, 6), "전산 대 물류",
                erp, wms, "base_quantity", BigDecimal.ZERO, null));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void snapshot(String source, String itemRef, String name, String qty) {
        var at = baseAt.atOffset(ZoneOffset.UTC);
        jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source, warehouse_code, lot_code,
                             quantity, base_quantity, base_unit, raw_item_name,
                             created_at, updated_at)
                        VALUES (:id, :tenant, :item, :at, :source, '', '',
                                :qty, :qty, 'EA', :name, :at, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("item", itemRef)
                .param("at", at)
                .param("source", source)
                .param("qty", new BigDecimal(qty))
                .param("name", name)
                .update();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder link(
            String... picks) {
        // 사장님이 쓰는 브라우저와 같은 조건으로 본다 — 문구가 사람 말인지가 이 시험의 요점이다.
        var request = post("/reconcile/units/link")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .locale(java.util.Locale.KOREAN)
                .param("definitionId", definition.getId().toString());
        for (String pick : picks) {
            request = request.param("pick", pick);
        }
        return request;
    }

    /** 이 시험이 없으면 이 화면을 만든 이유가 사라진다. */
    @Test
    @WithMockUser
    @DisplayName("이름이 서로 달라도 손으로 골라 이을 수 있다")
    void 이름이_달라도_잇는다() throws Exception {
        mockMvc.perform(link(erp + "|A0001", wms + "|SKU-77")
                        .param("pickFactor", "1").param("pickFactor", "1")
                        .param("newName", "클래식 227g"))
                .andExpect(status().is3xxRedirection());

        assertThat(members.findBySourceAndItemRef(erp, "A0001")).isPresent();
        assertThat(members.findBySourceAndItemRef(wms, "SKU-77")).isPresent();
        assertThat(members.findBySourceAndItemRef(erp, "A0001").orElseThrow().getUnitId())
                .as("같은 물건이어야 대조가 둘을 견준다")
                .isEqualTo(members.findBySourceAndItemRef(wms, "SKU-77").orElseThrow().getUnitId());
    }

    /**
     * <b>눈으로 보고 누른 것은 바로 확정된다.</b>
     *
     * <p>미리보기에서 품명·수량·계수를 다 본 뒤 누르는 길이라 확인을 한 번 더 시킬 이유가 없다.
     * 확인이 남으면 사람은 「이었는데 왜 대조에 안 들어가지」 로 만난다.
     */
    @Test
    @WithMockUser
    @DisplayName("손으로 이은 것은 바로 대조에 들어간다")
    void 손으로_이으면_바로_들어간다() throws Exception {
        mockMvc.perform(link(erp + "|A0001", wms + "|SKU-77")
                        .param("pickFactor", "1").param("pickFactor", "1"))
                .andExpect(status().is3xxRedirection());

        assertThat(aggregator.sumByUnit(TENANT, erp, baseAt, "base_quantity"))
                .as("확인이 남아 있으면 합산에 안 들어간다")
                .isNotEmpty();
    }

    /**
     * <b>계수가 곧 「몇 개로 셀지」다.</b>
     *
     * <p>물류가 박스로 세고 전산이 낱개로 세면 수량이 그대로는 안 맞는다. 계수를 넣을 자리가
     * 없으면 그 품목은 영영 「차이 남」 으로 남는다.
     */
    @Test
    @WithMockUser
    @DisplayName("몇 개로 셀지를 넣으면 그만큼 곱해 견준다")
    void 계수를_넣는다() throws Exception {
        // 물류 8박스 × 12.5 = 100 → 전산 100 과 맞는다
        mockMvc.perform(link(erp + "|A0001", wms + "|SKU-77")
                        .param("pickFactor", "1").param("pickFactor", "12.5"))
                .andExpect(status().is3xxRedirection());

        UUID unitId = members.findBySourceAndItemRef(wms, "SKU-77").orElseThrow().getUnitId();
        assertThat(aggregator.sumByUnit(TENANT, wms, baseAt, "base_quantity").get(unitId))
                .as("계수를 넣을 곳이 없으면 이 품목은 영영 «차이 남» 으로 남는다")
                .isEqualByComparingTo("100");
    }

    /**
     * <b>합치기 — 한쪽이 여럿인 경우.</b>
     *
     * <p>「전산의 세트 1개 = 물류의 낱개 여럿」 이 이 길로 풀린다. 이미 어느 물건에 속한 품목이
     * 담기면 새로 만들지 않고 그 물건에 붙인다. 새 표도 새 개념도 필요 없다.
     */
    @Test
    @WithMockUser
    @DisplayName("이미 이어 둔 물건에 더 담으면 합쳐진다")
    void 합치기() throws Exception {
        snapshot(wms, "SKU-78", "CLASSIC 227g 리필", "5");

        mockMvc.perform(link(erp + "|A0001", wms + "|SKU-77")
                .param("pickFactor", "1").param("pickFactor", "1"));
        UUID unitId = members.findBySourceAndItemRef(erp, "A0001").orElseThrow().getUnitId();

        mockMvc.perform(link(erp + "|A0001", wms + "|SKU-78")
                        .param("pickFactor", "1").param("pickFactor", "1"))
                .andExpect(status().is3xxRedirection());

        assertThat(members.findBySourceAndItemRef(wms, "SKU-78").orElseThrow().getUnitId())
                .as("새 물건을 만들지 않고 이미 있는 것에 붙어야 한다")
                .isEqualTo(unitId);
    }

    /**
     * 서로 <b>다른</b> 두 물건에 속한 품목을 함께 이으려 하면 막는다.
     *
     * <p>그것은 「두 물건을 합치는」 일인데, 어느 이름을 남길지·수량을 어떻게 볼지가 사람의
     * 판단이라 조용히 정해 버리면 안 된다. 막되 <b>무엇을 해야 하는지</b>까지 말한다.
     */
    @Test
    @WithMockUser
    @DisplayName("서로 다른 두 물건을 한꺼번에 합치려 하면 막고 할 일을 말한다")
    void 두_물건은_막는다() throws Exception {
        snapshot(erp, "A0002", "다른 것", "3");
        snapshot(wms, "SKU-79", "다른 것 B", "3");

        mockMvc.perform(link(erp + "|A0001", wms + "|SKU-77")
                .param("pickFactor", "1").param("pickFactor", "1"));
        mockMvc.perform(link(erp + "|A0002", wms + "|SKU-79")
                .param("pickFactor", "1").param("pickFactor", "1"));

        mockMvc.perform(link(erp + "|A0001", erp + "|A0002")
                        .param("pickFactor", "1").param("pickFactor", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attribute("flashError",
                                Matchers.containsString("먼저 끊은 뒤")));
    }

    /** 아무것도 안 담고 누르면 «무엇을 해야 하는지» 를 말한다. */
    @Test
    @WithMockUser
    @DisplayName("아무것도 안 담고 누르면 할 일을 말한다")
    void 빈_채로_누르면() throws Exception {
        mockMvc.perform(post("/reconcile/units/link")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(java.util.Locale.KOREAN)
                        .param("definitionId", definition.getId().toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attribute("flashError",
                                Matchers.containsString("골라 담으세요")));
    }

    /**
     * <b>담은 것이 검색을 살아남는가.</b>
     *
     * <p>이 화면의 핵심 약속이다. 담아 둔 것을 브라우저가 들고 있을 방법이 없어서(CSP) 한 폼에
     * 다 넣었는데, 그 구조가 실제로 도는지는 이 시험만 안다.
     */
    @Test
    @WithMockUser
    @DisplayName("검색해도 담아 둔 것이 남는다")
    void 검색해도_담은_것이_남는다() throws Exception {
        mockMvc.perform(post("/reconcile/units")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("definitionId", definition.getId().toString())
                        .param("pick", erp + "|A0001")
                        .param("pickFactor", "1")
                        .param("lq", "찾을수없는글자"))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                // 담아 둔 것이 그대로 있고
                .andExpect(content().string(Matchers.containsString("담아 둔 것")))
                .andExpect(content().string(Matchers.containsString("클래식 227")))
                // 검색 결과는 비어 있다
                .andExpect(content().string(Matchers.containsString("찾는 조건에 맞는 품목이 없습니다")));
    }

    /** 담긴 재고에 없는 것을 담으면 조용히 빠지지 않고 그 사실을 말한다. */
    @Test
    @WithMockUser
    @DisplayName("담긴 재고에 없는 품목은 그 사실을 말한다")
    void 없는_것을_담으면() throws Exception {
        mockMvc.perform(post("/reconcile/units")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("definitionId", definition.getId().toString())
                        .param("pick", erp + "|없는코드")
                        .param("pickFactor", "1"))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(content().string(Matchers.containsString("지금 담긴 재고에 없습니다")));
    }

    /**
     * 확인은 <b>물건 통째로</b> 한다.
     *
     * <p>한쪽만 확정하면 합산이 「좌 120 · 우 0」 이 되어 대조가 매일 유령 차이를 올리고,
     * 사람은 그것을 매칭 문제가 아니라 재고 사고로 읽는다.
     */
    @Test
    @WithMockUser
    @DisplayName("확인하면 그 물건의 품목이 전부 확정된다")
    void 확인은_물건_단위() throws Exception {
        var unit = unitService.create("U-" + UUID.randomUUID().toString().substring(0, 6),
                "묶음", "EA");
        unitService.propose(unit.getId(), erp, "A0001", BigDecimal.ONE);
        unitService.propose(unit.getId(), wms, "SKU-77", BigDecimal.ONE);

        mockMvc.perform(post("/reconcile/units/{id}/confirm", unit.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());

        List<?> stillPending = members.findByUnitIdAndConfirmedAtIsNull(unit.getId());
        assertThat(stillPending)
                .as("반쪽만 확정되면 대조가 매일 유령 차이를 올린다")
                .isEmpty();
    }
}
