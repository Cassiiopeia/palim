package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.engine.SnapshotAggregator;
import kr.suhsaechan.palim.reconcile.match.MatchBoard;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitMemberRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * <b>이름이 달라도 이을 수 있는가, 그리고 여러 개를 한꺼번에 다룰 수 있는가.</b>
 *
 * <p>여기가 이 화면의 존재 이유다. 자동 후보는 다듬은 이름이 <b>정확히</b> 같은 것만 묶는데,
 * 실제로 안 이어지는 품목은 이름이 다른 것들이다. 손으로 고르는 자리가 없으면 사람이
 * 「저 둘이 같은 거야」 라고 알고 있어도 화면에 그 품목이 나타나지조차 않는다.
 *
 * <p>그리고 <b>한쪽에만 있는 줄을 그대로 이으면 안 된다.</b> 합산이 「좌 120 · 우 0」 이 되어
 * 대조가 매일 전량 차이를 올리는데, 사람은 그것을 매칭 문제가 아니라 재고 사고로 읽는다.
 * 그 줄에는 「짝 없음으로 두기」 가 제 자리이고, 그래야 남은 일이 0에 도달한다.
 */
@AutoConfigureMockMvc
class ManualLinkIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ReconcileDefinitionRepository definitions;
    @Autowired private ReconcileUnitMemberRepository members;
    @Autowired private SnapshotAggregator aggregator;
    @Autowired private MatchBoard board;
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

    /** 화면이 그 품목에 붙여 놓은 줄 열쇠. 폼이 보내는 값과 같은 것을 시험도 쓴다. */
    private String rowKeyOf(String source, String itemRef) {
        return board.findRowByItem(TENANT, erp, wms, MatchBoard.tokenOf(source, itemRef))
                .orElseThrow(() -> new IllegalStateException("줄을 못 찾았다: " + itemRef))
                .key();
    }

    /** 사장님이 쓰는 브라우저와 같은 조건으로 본다 — 문구가 사람 말인지가 이 시험의 요점이다. */
    private MockHttpServletRequestBuilder action(String path, Object... vars) {
        return post(path, vars)
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .locale(Locale.KOREAN)
                .param("definitionId", definition.getId().toString());
    }

    private void pair(String rowSource, String rowItem, String mateSource, String mateItem)
            throws Exception {
        mockMvc.perform(action("/reconcile/units/pair")
                        .param("rowKey", rowKeyOf(rowSource, rowItem))
                        .param("mate", MatchBoard.tokenOf(mateSource, mateItem)))
                .andExpect(status().is3xxRedirection());
    }

    /** 이 시험이 없으면 이 화면을 만든 이유가 사라진다. */
    @Test
    @WithMockUser
    @DisplayName("이름이 서로 달라도 줄 안에서 짝을 골라 이을 수 있다")
    void 이름이_달라도_잇는다() throws Exception {
        pair(erp, "A0001", wms, "SKU-77");

        assertThat(members.findBySourceAndItemRef(erp, "A0001")).isPresent();
        assertThat(members.findBySourceAndItemRef(wms, "SKU-77")).isPresent();
        assertThat(members.findBySourceAndItemRef(erp, "A0001").orElseThrow().getUnitId())
                .as("같은 물건이어야 대조가 둘을 견준다")
                .isEqualTo(members.findBySourceAndItemRef(wms, "SKU-77").orElseThrow().getUnitId());
    }

    /**
     * <b>표에서 좌·우와 차이를 보고 누른 것은 바로 확정된다.</b>
     *
     * <p>확인 단계가 따로 필요했던 것은 화면이 좌·우를 나란히 못 보여주던 때다. 지금은 누르기
     * 전에 그 줄에서 판단이 서므로, 확인이 남아 있으면 사람은 「이었는데 왜 대조에 안 들어가지」
     * 로 만난다.
     */
    @Test
    @WithMockUser
    @DisplayName("이은 것은 바로 대조에 들어간다")
    void 이으면_바로_들어간다() throws Exception {
        pair(erp, "A0001", wms, "SKU-77");

        assertThat(aggregator.sumByUnit(TENANT, erp, baseAt, "base_quantity"))
                .as("확인이 남아 있으면 합산에 안 들어간다")
                .isNotEmpty();
    }

    /**
     * <b>계수가 곧 「몇 개로 셀지」다.</b>
     *
     * <p>물류가 박스로 세고 전산이 낱개로 세면 수량이 그대로는 안 맞는다. 고칠 자리가 없으면
     * 그 품목은 영영 「차이 남」 으로 남는다.
     */
    @Test
    @WithMockUser
    @DisplayName("몇 개로 셀지를 고치면 그만큼 곱해 견준다")
    void 계수를_고친다() throws Exception {
        pair(erp, "A0001", wms, "SKU-77");

        UUID memberId = members.findBySourceAndItemRef(wms, "SKU-77").orElseThrow().getId();
        // 물류 8박스 × 12.5 = 100 → 전산 100 과 맞는다
        mockMvc.perform(action("/reconcile/units/members/{id}/factor", memberId)
                        .param("factor", "12.5"))
                .andExpect(status().is3xxRedirection());

        UUID unitId = members.findBySourceAndItemRef(wms, "SKU-77").orElseThrow().getUnitId();
        assertThat(aggregator.sumByUnit(TENANT, wms, baseAt, "base_quantity").get(unitId))
                .as("계수를 고칠 곳이 없으면 이 품목은 영영 «차이 남» 으로 남는다")
                .isEqualByComparingTo("100");
    }

    /**
     * <b>합치기 — 한쪽이 여럿인 경우.</b>
     *
     * <p>「전산의 세트 1개 = 물류의 낱개 여럿」 이 이 길로 풀린다. 이어 둔 줄에서 「더 잇기」 를
     * 누르면 새 물건을 만들지 않고 그 물건에 붙는다. 새 표도 새 개념도 필요 없다.
     */
    @Test
    @WithMockUser
    @DisplayName("이어 둔 물건에 더 담으면 합쳐진다")
    void 합치기() throws Exception {
        snapshot(wms, "SKU-78", "CLASSIC 227g 리필", "5");

        pair(erp, "A0001", wms, "SKU-77");
        UUID unitId = members.findBySourceAndItemRef(erp, "A0001").orElseThrow().getUnitId();

        pair(erp, "A0001", wms, "SKU-78");

        assertThat(members.findBySourceAndItemRef(wms, "SKU-78").orElseThrow().getUnitId())
                .as("새 물건을 만들지 않고 이미 있는 것에 붙어야 한다")
                .isEqualTo(unitId);
    }

    /**
     * <b>한쪽에만 있는 줄은 잇지 않는다.</b>
     *
     * <p>이으면 합산이 「좌 120 · 우 0」 이 되어 대조가 매일 전량 차이를 올린다. 막되 무엇을
     * 해야 하는지까지 말해야 사람이 다음 걸음을 안다.
     */
    @Test
    @WithMockUser
    @DisplayName("한쪽에만 있는 줄은 잇지 않고 할 일을 말한다")
    void 한쪽만_있으면_막는다() throws Exception {
        snapshot(erp, "A0009", "이쪽에만 있는 것", "7");
        String key = rowKeyOf(erp, "A0009");

        mockMvc.perform(action("/reconcile/units/link").param("rows", key))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError",
                        Matchers.containsString("짝 없음으로 두기")));

        assertThat(members.findBySourceAndItemRef(erp, "A0009"))
                .as("반쪽짜리 물건이 만들어지면 대조가 매일 유령 차이를 올린다")
                .isEmpty();
    }

    /**
     * <b>여러 줄을 한꺼번에.</b>
     *
     * <p>품목이 수십 개일 때 한 줄씩 스무 번 누르는 것과 고르고 한 번 누르는 것은 다른 일이다.
     */
    @Test
    @WithMockUser
    @DisplayName("고른 여러 줄을 한꺼번에 잇는다")
    void 여러_줄을_한꺼번에() throws Exception {
        // 이름이 같아 자동으로 짝이 잡히는 줄 둘
        snapshot(erp, "B1", "코코아 227g", "10");
        snapshot(wms, "B1W", "코코아 227g", "10");
        snapshot(erp, "B2", "노슈거 198g", "20");
        snapshot(wms, "B2W", "노슈거 198g", "20");

        mockMvc.perform(action("/reconcile/units/link")
                        .param("rows", rowKeyOf(erp, "B1"))
                        .param("rows", rowKeyOf(erp, "B2")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashSuccess",
                        Matchers.containsString("2개를 이었습니다")));

        assertThat(members.findBySourceAndItemRef(wms, "B1W")).isPresent();
        assertThat(members.findBySourceAndItemRef(wms, "B2W")).isPresent();
        assertThat(members.findBySourceAndItemRef(erp, "B1").orElseThrow().getUnitId())
                .as("줄마다 한 물건이다 — 고른 줄이 통째로 하나가 되면 안 된다")
                .isNotEqualTo(members.findBySourceAndItemRef(erp, "B2").orElseThrow().getUnitId());
    }

    /**
     * <b>짝 없음으로 두면 남은 일에서 빠지고, 되돌릴 수 있다.</b>
     *
     * <p>이 길이 없으면 남은 일 개수가 영영 0이 되지 않는다 — 한쪽에만 있는 품목은 늘 남기
     * 때문이다. 도달하지 못하는 숫자는 사람이 곧 안 보게 된다.
     */
    @Test
    @WithMockUser
    @DisplayName("짝 없음으로 두면 할 일에서 빠지고 되돌릴 수 있다")
    void 짝_없음으로_두고_되돌린다() throws Exception {
        snapshot(erp, "A0009", "이쪽에만 있는 것", "7");
        String key = rowKeyOf(erp, "A0009");

        mockMvc.perform(action("/reconcile/units/set-aside")
                        .param("rows", key)
                        .param("reason", "DISCONTINUED"))
                .andExpect(status().is3xxRedirection());

        var afterSetAside = board.load(TENANT, erp, wms, MatchBoard.Tab.TODO, null, 0);
        assertThat(afterSetAside.counts().setAside()).isEqualTo(1);
        assertThat(afterSetAside.rows())
                .as("짝 없음으로 둔 것이 할 일에 남아 있으면 개수가 0에 도달하지 못한다")
                .noneMatch(row -> row.items().stream()
                        .anyMatch(item -> item.itemRef().equals("A0009")));

        String setAsideKey = rowKeyOf(erp, "A0009");
        mockMvc.perform(action("/reconcile/units/restore").param("rows", setAsideKey))
                .andExpect(status().is3xxRedirection());

        assertThat(board.load(TENANT, erp, wms, MatchBoard.Tab.TODO, null, 0).counts().setAside())
                .as("단종인 줄 알았는데 다시 들어오는 일이 실제로 있다")
                .isZero();
    }

    /**
     * <b>풀면 다시 이을 수 있다.</b>
     *
     * <p>되돌릴 길이 없으면 잘못 이은 것이 영영 남는다. 한 품목은 한 물건에만 속하므로 그
     * 품목을 다시 이을 수도 없어 막다른 길이 된다.
     */
    @Test
    @WithMockUser
    @DisplayName("이어 둔 것을 풀면 다시 할 일로 돌아온다")
    void 풀면_돌아온다() throws Exception {
        pair(erp, "A0001", wms, "SKU-77");
        UUID unitId = members.findBySourceAndItemRef(erp, "A0001").orElseThrow().getUnitId();

        mockMvc.perform(action("/reconcile/units/{id}/unlink", unitId))
                .andExpect(status().is3xxRedirection());

        assertThat(members.findBySourceAndItemRef(erp, "A0001")).isEmpty();
        assertThat(members.findBySourceAndItemRef(wms, "SKU-77"))
                .as("한 품목만 떼면 반쪽이 남아 대조가 매일 유령 차이를 올린다")
                .isEmpty();
    }

    /**
     * <b>누른 뒤 표 자리로 돌아온다.</b>
     *
     * <p>돌아갈 자리를 안 알려 주면 매번 화면 맨 위로 올라가, 방금 무엇을 눌렀는지 놓친다.
     * 이을 것이 스무 개면 스무 번 그렇게 된다.
     */
    @Test
    @WithMockUser
    @DisplayName("누른 뒤 보던 갈래와 표 자리로 돌아온다")
    void 보던_자리로_돌아온다() throws Exception {
        snapshot(erp, "A0009", "이쪽에만 있는 것", "7");

        mockMvc.perform(action("/reconcile/units/set-aside")
                        .param("rows", rowKeyOf(erp, "A0009"))
                        .param("tab", "ONE_SIDED")
                        .param("q", "이쪽"))
                .andExpect(redirectedUrl("/reconcile/units?definitionId=" + definition.getId()
                        + "&tab=ONE_SIDED&q=%EC%9D%B4%EC%AA%BD#board"));
    }

    /** 아무것도 안 고르고 누르면 «무엇을 해야 하는지» 를 말한다. */
    @Test
    @WithMockUser
    @DisplayName("아무것도 안 고르고 누르면 할 일을 말한다")
    void 빈_채로_누르면() throws Exception {
        mockMvc.perform(action("/reconcile/units/link"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError",
                        Matchers.containsString("고르지 않았습니다")));
    }

    /**
     * <b>줄 안에서 짝 후보가 이름이 닮은 순서로 나온다.</b>
     *
     * <p>자동 후보는 다듬은 이름이 정확히 같아야 잡히므로 「클래식 227」 과 「CLASSIC 227g」 는
     * 영영 못 만난다. 그래도 목록 맨 앞에는 놓아야 사람이 목록 전체를 눈으로 훑지 않는다.
     */
    @Test
    @WithMockUser
    @DisplayName("줄을 펼치면 반대쪽 후보가 그 자리에 나온다")
    void 줄_안에서_고른다() throws Exception {
        snapshot(erp, "A0009", "이쪽에만 있는 것", "7");

        mockMvc.perform(get("/reconcile/units")
                        .locale(Locale.KOREAN)
                        .param("definitionId", definition.getId().toString())
                        .param("tab", "ONE_SIDED")
                        .param("expand", rowKeyOf(erp, "A0009")))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                .andExpect(content().string(Matchers.containsString("의 짝을")))
                .andExpect(content().string(Matchers.containsString("CLASSIC 227g")));
    }

    /**
     * <b>차이가 잇기 버튼 옆에 있다.</b>
     *
     * <p>없으면 사람이 여러 줄을 눈으로 더해야 하고, 결국 잇고 나서 대조를 돌려야 처음 보인다 —
     * 그때는 이미 늦다.
     */
    @Test
    @WithMockUser
    @DisplayName("짝이 잡힌 줄은 잇기 전에 수량 차이를 보여준다")
    void 잇기_전에_차이를_본다() throws Exception {
        snapshot(erp, "C1", "코코아 227g", "100");
        snapshot(wms, "C1W", "코코아 227g", "88");

        mockMvc.perform(get("/reconcile/units")
                        .locale(Locale.KOREAN)
                        .param("definitionId", definition.getId().toString())
                        .param("tab", "PAIRED"))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(content().string(Matchers.containsString("+12")))
                .andExpect(content().string(Matchers.containsString("이 둘 잇기")));
    }
}
