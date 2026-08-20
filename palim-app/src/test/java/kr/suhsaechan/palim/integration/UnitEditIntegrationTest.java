package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import kr.suhsaechan.palim.reconcile.define.Pairing;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.match.BreakdownAxis;
import kr.suhsaechan.palim.reconcile.match.MatchBoard;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnit;
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
 * 묶음을 <b>품목 하나 단위로</b> 손댈 수 있는가.
 *
 * <p>여태 묶음을 <b>통째로만</b> 다룰 수 있었다 — 통째로 만들고, 통째로 풀었다. 그래서 로트
 * 셋이 든 묶음에서 하나만 빼려면 <b>묶음을 통째로 풀고 다시 묶어야</b> 했고, 셋을 셋으로
 * 쪼개거나 두 묶음을 하나로 보는 일은 아예 불가능했다.
 *
 * <p>결과가 통째로 「+50 하나」 로만 보였던 것도 같은 뿌리다. 로트별로 갈라 묶으면
 * 「+24 · +26 · 맞음」 이 되는데, <b>어느 쪽이 맞는 운영인지는 회사가 정할 일</b>이다.
 * 고를 자리가 없으면 코드가 정해 버린 셈이 된다.
 */
@AutoConfigureMockMvc
class UnitEditIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ReconcileDefinitionRepository definitions;
    @Autowired private ReconcileUnitService unitService;
    @Autowired private ReconcileUnitMemberRepository members;
    @Autowired private MatchBoard board;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private kr.suhsaechan.palim.reconcile.rule.NormalizationRuleRepository normalizationRules;
    @Autowired private kr.suhsaechan.palim.reconcile.rule.NormalizationEngine normalizer;

    private ReconcileDefinition definition;
    private String erp;
    private String wms;
    private Instant baseAt;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        baseAt = Instant.now().truncatedTo(ChronoUnit.DAYS);
        erp = "erp-" + UUID.randomUUID().toString().substring(0, 6);
        wms = "wms-" + UUID.randomUUID().toString().substring(0, 6);

        // 로트 셋이 든 줄 — 통째로 보면 +50, 갈라 보면 +24 · +26 · 맞음.
        snapshot(erp, "N198_26.11.26", "노슈거 198g (26.11.26)", "449");
        snapshot(erp, "N198_27.10.14", "노슈거 198g (27.10.14)", "1582");
        snapshot(erp, "N198_27.10.15", "노슈거 198g (27.10.15)", "246");
        snapshot(wms, "00094", "노슈거 198g (26.11.26)", "425");
        snapshot(wms, "01002", "노슈거 198g (27.10.14)", "1556");
        snapshot(wms, "01004", "노슈거 198g (27.10.15)", "246");

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
                .param("at", baseAt.atOffset(ZoneOffset.UTC))
                .param("source", source)
                .param("qty", new BigDecimal(qty))
                .param("name", name)
                .update();
    }

    /** 로트 셋을 통째로 한 묶음으로 만든 상태. */
    private ReconcileUnit wholeUnit() {
        return unitService.link(List.of(
                        new ReconcileUnitService.Pick(erp, "N198_26.11.26", BigDecimal.ONE),
                        new ReconcileUnitService.Pick(erp, "N198_27.10.14", BigDecimal.ONE),
                        new ReconcileUnitService.Pick(erp, "N198_27.10.15", BigDecimal.ONE),
                        new ReconcileUnitService.Pick(wms, "00094", BigDecimal.ONE),
                        new ReconcileUnitService.Pick(wms, "01002", BigDecimal.ONE),
                        new ReconcileUnitService.Pick(wms, "01004", BigDecimal.ONE)),
                "U-" + UUID.randomUUID().toString().substring(0, 8), "노슈거 198g", "EA");
    }

    /**
     * 이 시험이 만든 묶음만 센다.
     *
     * <p>묶음은 <b>테넌트 전역</b>이라 다른 시험이 남긴 것이 함께 잡힌다. 전체 개수로 견주면
     * 시험이 혼자 돌 때만 통과하고 함께 돌면 깨진다.
     */
    private List<UUID> myUnits() {
        return unitService.activeUnits().stream()
                .map(ReconcileUnit::getId)
                .filter(id -> members.findByUnitIdOrderBySource(id).stream()
                        .anyMatch(m -> m.getSource().equals(erp) || m.getSource().equals(wms)))
                .toList();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder action(
            String path, Object... vars) {
        return post(path, vars)
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .locale(Locale.KOREAN)
                .param("definitionId", definition.getId().toString());
    }

    /**
     * <b>품목 하나만 빼기.</b>
     *
     * <p>이 길이 없어서 로트 셋 중 하나가 잘못 들어가면 묶음을 통째로 풀고 다시 묶어야 했다 —
     * 하나 때문에 나머지 둘까지 다시 하는 것이다.
     */

    /**
     * 괄호 안 유통기한을 떼는 규칙.
     *
     * <p>이 규칙은 <b>꺼진 채로 설치된다</b> — 소비기한으로만 구분되는 서로 다른 품목이 있는
     * 곳에서 그것들을 한 물건으로 만들기 때문이다. 그래서 이 규칙이 있어야 성립하는 시험은
     * 자기 전제를 직접 만든다.
     */
    private void bracketRule() {
        normalizationRules.save(kr.suhsaechan.palim.reconcile.rule.NormalizationRule.of(
                TENANT, "시험용 괄호 제거", "\\([^)]*\\)", "", 1));
        normalizer.clearCache();
    }

    @Test
    @WithMockUser
    @DisplayName("품목 하나만 빼면 나머지는 그대로 남는다")
    void 하나만_뺀다() throws Exception {
        ReconcileUnit unit = wholeUnit();
        UUID memberId = members.findBySourceAndItemRef(erp, "N198_26.11.26").orElseThrow().getId();

        mockMvc.perform(action("/reconcile/units/members/{id}/remove", memberId)
                        .param("unitId", unit.getId().toString()))
                .andExpect(status().is3xxRedirection());

        assertThat(members.findBySourceAndItemRef(erp, "N198_26.11.26")).isEmpty();
        assertThat(members.findByUnitIdOrderBySource(unit.getId()))
                .as("하나를 뺐다고 나머지가 흩어지면 안 된다")
                .hasSize(5);
    }

    /** 품목 하나를 이 묶음에 넣는다 — 새 묶음이 생기면 안 된다. */
    @Test
    @WithMockUser
    @DisplayName("품목을 넣으면 새 묶음이 생기지 않고 이 묶음에 붙는다")
    void 하나만_넣는다() throws Exception {
        ReconcileUnit unit = unitService.link(List.of(
                        new ReconcileUnitService.Pick(erp, "N198_26.11.26", BigDecimal.ONE),
                        new ReconcileUnitService.Pick(wms, "00094", BigDecimal.ONE)),
                "U-" + UUID.randomUUID().toString().substring(0, 8), "노슈거 198g", "EA");
        int before = myUnits().size();

        mockMvc.perform(action("/reconcile/units/{id}/add", unit.getId())
                        .param("token", MatchBoard.tokenOf(erp, "N198_27.10.14")))
                .andExpect(status().is3xxRedirection());

        assertThat(members.findBySourceAndItemRef(erp, "N198_27.10.14").orElseThrow().getUnitId())
                .isEqualTo(unit.getId());
        assertThat(myUnits())
                .as("붙일 묶음이 정해져 있는데 새 묶음이 생기면 목록에 유령이 쌓인다")
                .hasSize(before);
    }

    /**
     * <b>묶음 쪼개기.</b>
     *
     * <p>「+50 하나」 로 볼지 「+24 · +26 · 맞음」 셋으로 볼지는 회사가 정할 일이다.
     */
    @Test
    @WithMockUser
    @DisplayName("묶음을 로트별로 쪼개면 각각 따로 견준다")
    void 쪼갠다() throws Exception {
        ReconcileUnit unit = wholeUnit();

        mockMvc.perform(action("/reconcile/units/{id}/split", unit.getId())
                        .param("axis", BreakdownAxis.byName().token()))
                .andExpect(status().is3xxRedirection());

        assertThat(myUnits())
                .as("로트 셋이 각각 한 묶음이 되어야 한다")
                .hasSize(3);
        // 27.10.15 는 양쪽 246 이라 맞는다 — 통째로 보면 이 사실이 +50 에 묻힌다.
        UUID matched = members.findBySourceAndItemRef(erp, "N198_27.10.15").orElseThrow().getUnitId();
        assertThat(members.findBySourceAndItemRef(wms, "01004").orElseThrow().getUnitId())
                .isEqualTo(matched);
        assertThat(members.findBySourceAndItemRef(erp, "N198_26.11.26").orElseThrow().getUnitId())
                .as("다른 로트가 같은 묶음에 남아 있으면 쪼갠 것이 아니다")
                .isNotEqualTo(matched);
    }

    /**
     * <b>묶음끼리 합치기.</b>
     *
     * <p>여태 「이미 서로 다른 묶음에 속한 품목입니다」 로 <b>막히기만 했다.</b> 막기만 하고
     * 할 길을 안 주면 그건 그냥 못 하는 일이다.
     */
    @Test
    @WithMockUser
    @DisplayName("두 묶음을 하나로 합칠 수 있다")
    void 합친다() throws Exception {
        ReconcileUnit keep = unitService.link(List.of(
                        new ReconcileUnitService.Pick(erp, "N198_26.11.26", BigDecimal.ONE),
                        new ReconcileUnitService.Pick(wms, "00094", BigDecimal.ONE)),
                "U-" + UUID.randomUUID().toString().substring(0, 8), "노슈거 198g", "EA");
        ReconcileUnit gone = unitService.link(List.of(
                        new ReconcileUnitService.Pick(erp, "N198_27.10.14", BigDecimal.ONE),
                        new ReconcileUnitService.Pick(wms, "01002", BigDecimal.ONE)),
                "U-" + UUID.randomUUID().toString().substring(0, 8), "노슈거 198g 다른 로트", "EA");

        mockMvc.perform(action("/reconcile/units/{id}/merge", keep.getId())
                        .param("otherUnitId", gone.getId().toString()))
                .andExpect(status().is3xxRedirection());

        assertThat(members.findBySourceAndItemRef(erp, "N198_27.10.14").orElseThrow().getUnitId())
                .as("합친 쪽 품목이 옮겨 와야 한다")
                .isEqualTo(keep.getId());
        assertThat(myUnits())
                .as("빈 묶음이 목록에 남으면 무엇이 진짜인지 흐려진다")
                .hasSize(1);
    }

    /**
     * <b>나눠서 묶기</b> — 아직 안 묶인 줄도 통째로만 묶이면 안 된다.
     *
     * <p>자동 후보는 이름이 닮은 것을 통째로 하나로 제안한다. 고를 자리가 없으면 코드가
     * 정해 버린다.
     */
    @Test
    @WithMockUser
    @DisplayName("아직 안 묶인 줄을 나눠서 여러 묶음으로 묶는다")
    void 나눠서_묶는다() throws Exception {
        // 이 시험은 괄호 규칙이 있어야 성립한다. 기본 규칙은 꺼진 채로 설치된다.
        bracketRule();
        String key = board.load(TENANT, Pairing.ofSources(erp, wms), MatchBoard.Tab.PAIRED, null, 0)
                .rows().getFirst().key();

        mockMvc.perform(post("/reconcile/units/split-link")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("definitionId", definition.getId().toString())
                        .param("row", key)
                        .param("axis", BreakdownAxis.byName().token()))
                .andExpect(status().is3xxRedirection());

        assertThat(myUnits())
                .as("통째로 하나만 되면 코드가 정해 버리는 셈이다")
                .hasSize(3);
    }

    /** 가르기 전에 결과를 보여준다 — 되돌리려면 다시 합쳐야 하므로. */
    @Test
    @WithMockUser
    @DisplayName("나누기 전에 어떤 묶음들이 되는지 미리 보여준다")
    void 나누기_전에_미리본다() throws Exception {
        // 이 시험은 괄호 규칙이 있어야 성립한다. 기본 규칙은 꺼진 채로 설치된다.
        bracketRule();
        String key = board.load(TENANT, Pairing.ofSources(erp, wms), MatchBoard.Tab.PAIRED, null, 0)
                .rows().getFirst().key();

        mockMvc.perform(get("/reconcile/units/split-preview")
                        .locale(Locale.KOREAN)
                        .param("definitionId", definition.getId().toString())
                        .param("row", key))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                .andExpect(content().string(Matchers.containsString("통째로 하나로 묶으면")))
                .andExpect(content().string(Matchers.containsString("3개 묶음이 됩니다")))
                // 갈랐을 때 각각 차이가 얼마인지 보여야 판단이 선다
                .andExpect(content().string(Matchers.containsString("+24")))
                .andExpect(content().string(Matchers.containsString("맞음")));

        assertThat(myUnits())
                .as("미리보기가 자료를 바꾸면 안 된다")
                .isEmpty();
    }

    /** 편집 화면이 든 품목을 좌·우로 갈라 보여주고, 손댈 자리를 전부 준다. */
    @Test
    @WithMockUser
    @DisplayName("편집 화면이 빼기·넣기·쪼개기·합치기·풀기를 모두 준다")
    void 편집_화면이_열린다() throws Exception {
        ReconcileUnit unit = wholeUnit();

        mockMvc.perform(get("/reconcile/units/{id}/edit", unit.getId())
                        .locale(Locale.KOREAN)
                        .param("definitionId", definition.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                .andExpect(content().string(Matchers.containsString("이 묶음에 든 품목")))
                .andExpect(content().string(Matchers.containsString("N198_26.11.26")))
                .andExpect(content().string(Matchers.containsString("+ 품목 넣기")))
                .andExpect(content().string(Matchers.containsString("갈라서 여러 묶음으로")))
                .andExpect(content().string(Matchers.containsString("묶음 통째로 풀기")));
    }
}
