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
import kr.suhsaechan.palim.reconcile.define.WarehouseScope;
import kr.suhsaechan.palim.reconcile.define.Pairing;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.engine.ReconcileEngine;
import kr.suhsaechan.palim.reconcile.match.BreakdownAxis;
import kr.suhsaechan.palim.reconcile.match.MatchBoard;
import kr.suhsaechan.palim.reconcile.match.UnitBreakdown;
import kr.suhsaechan.palim.reconcile.match.UnitNaming;
import kr.suhsaechan.palim.reconcile.run.ReconcileRun;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnit;
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
 * <b>합계 하나로는 무슨 일인지 알 수 없다.</b>
 *
 * <p>실제로 겪은 것: 「클래식 850g · 전산 318 · 물류 307 · +11」. 이 11의 정체는 <b>물류가
 * 오래된 로트 3종을 이미 0으로 털었고 최신 로트는 정확히 맞는다</b> 였다. 「재고가 11개 빈다」
 * 와는 전혀 다른 이야기이고 할 일도 다른데, 화면은 숫자 하나만 보여줬다.
 *
 * <p>그리고 물건 이름 자리에 <b>코드가 나왔다</b> — 「U-6668d23b」. 이름은 이미 있었는데 화면이
 * 코드를 그리고 있었고, 그 이름조차 첫 품목 것을 그대로 써서 특정 로트 날짜가 전체를
 * 대표하고 있었다.
 */
@AutoConfigureMockMvc
class UnitBreakdownIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ReconcileDefinitionRepository definitions;
    @Autowired private ReconcileUnitService unitService;
    @Autowired private ReconcileEngine engine;
    @Autowired private UnitBreakdown breakdowns;
    @Autowired private UnitNaming naming;
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
        // 자정으로 맞춘다 — 대조가 「하루」 눈금으로 칸을 잡으므로 그 칸에 자료가 있어야 한다.
        baseAt = Instant.now().truncatedTo(ChronoUnit.DAYS);
        erp = "erp-" + UUID.randomUUID().toString().substring(0, 6);
        wms = "wms-" + UUID.randomUUID().toString().substring(0, 6);

        // 실제로 겪은 모양 그대로 — 로트 넷 중 셋은 물류가 이미 털었고, 최신 하나만 맞는다.
        snapshot(erp, "C850P_27.03.16", "클래식 850g (27.03.16)", "5");
        snapshot(erp, "C850P_27.04.16", "클래식 850g (27.04.16)", "2");
        snapshot(erp, "C850P_27.04.20", "클래식 850g (27.04.20)", "4");
        snapshot(erp, "C850P_27.11.16", "클래식 850g (27.11.16)", "307");
        snapshot(wms, "00090", "클래식 850g (27.03.16)", "0");
        snapshot(wms, "00091", "클래식 850g (27.04.16)", "0");
        snapshot(wms, "00092", "클래식 850g (27.04.20)", "0");
        snapshot(wms, "01005", "클래식 850g (27.11.16)", "307");

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
        snapshot(source, itemRef, name, qty, "");
    }

    /** 창고를 지정해 담는다. 스냅샷의 자연키에 창고가 들어 있어 같은 품목을 창고별로 담을 수 있다. */
    private void snapshot(String source, String itemRef, String name, String qty,
                          String warehouse) {
        var at = baseAt.atOffset(ZoneOffset.UTC);
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
                .param("at", at)
                .param("source", source)
                .param("warehouse", warehouse)
                .param("qty", new BigDecimal(qty))
                .param("name", name)
                .update();
    }

    /** 로트 넷을 한 물건으로 이어 둔 상태를 만든다. */
    private ReconcileUnit linkedUnit() {
        var picks = List.of(
                new ReconcileUnitService.Pick(erp, "C850P_27.03.16", BigDecimal.ONE),
                new ReconcileUnitService.Pick(erp, "C850P_27.04.16", BigDecimal.ONE),
                new ReconcileUnitService.Pick(erp, "C850P_27.04.20", BigDecimal.ONE),
                new ReconcileUnitService.Pick(erp, "C850P_27.11.16", BigDecimal.ONE),
                new ReconcileUnitService.Pick(wms, "00090", BigDecimal.ONE),
                new ReconcileUnitService.Pick(wms, "00091", BigDecimal.ONE),
                new ReconcileUnitService.Pick(wms, "00092", BigDecimal.ONE),
                new ReconcileUnitService.Pick(wms, "01005", BigDecimal.ONE));
        return unitService.link(picks, "U-" + UUID.randomUUID().toString().substring(0, 8),
                "클래식 850g", "EA");
    }

    /**
     * <b>이것이 이 화면의 존재 이유다.</b>
     *
     * <p>+11 이 어디서 왔는지를 로트별로 갈라 보여준다. 갈라 보지 않으면 「재고가 빈다」 로
     * 읽히고, 사람은 있지도 않은 재고 사고를 쫓게 된다.
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
    @DisplayName("합계 뒤에 무엇이 있는지 로트별로 갈라 보여준다")
    void 로트별로_갈라_본다() {
        ReconcileUnit unit = linkedUnit();

        UnitBreakdown.Breakdown result = breakdowns.of(TENANT, unit.getId(), Pairing.ofSources(erp, wms),
                UnitBreakdown.At.of(baseAt, baseAt, baseAt), BreakdownAxis.byName());

        assertThat(result.lines()).hasSize(4);
        assertThat(result.leftTotal()).isEqualByComparingTo("318");
        assertThat(result.rightTotal()).isEqualByComparingTo("307");
        assertThat(result.differing())
                .as("넷 중 셋이 어긋나고 최신 로트 하나는 맞는다 — 이것이 +11 의 정체다")
                .isEqualTo(3);
        assertThat(result.orphans()).isZero();

        // 같은 로트끼리 짝지어야 «무엇과 무엇이» 다른지 보인다. 다듬은 이름으로 견주면
        // 날짜가 떨어져 나가 네 로트가 전부 같은 이름이 되고 짝을 가릴 수 없다.
        assertThat(result.lines())
                .allSatisfy(line -> assertThat(line.left().displayName())
                        .isEqualTo(line.right().displayName()));
    }

    /**
     * 한쪽에 품목이 더 붙어 있으면 <b>그 줄이 드러나야 한다.</b>
     *
     * <p>대개 잘못 묶어 둔 것인데, 합계만 보면 그냥 차이 나는 줄로 보인다.
     */
    @Test
    @DisplayName("짝이 없는 품목은 한쪽만 있는 줄로 드러난다")
    void 짝_없는_품목이_드러난다() {
        snapshot(wms, "00101", "클래식 850g (26.05.05)", "0");
        var picks = new java.util.ArrayList<>(List.of(
                new ReconcileUnitService.Pick(erp, "C850P_27.03.16", BigDecimal.ONE),
                new ReconcileUnitService.Pick(wms, "00090", BigDecimal.ONE),
                new ReconcileUnitService.Pick(wms, "00101", BigDecimal.ONE)));
        ReconcileUnit unit = unitService.link(picks,
                "U-" + UUID.randomUUID().toString().substring(0, 8), "클래식 850g", "EA");

        UnitBreakdown.Breakdown result = breakdowns.of(TENANT, unit.getId(), Pairing.ofSources(erp, wms),
                UnitBreakdown.At.of(baseAt, baseAt, baseAt), BreakdownAxis.byName());

        assertThat(result.orphans())
                .as("한쪽에 하나 더 붙어 있다는 사실이 안 보이면 잘못 이은 것을 못 찾는다")
                .isEqualTo(1);
        assertThat(result.lines())
                .filteredOn(line -> !line.paired())
                .singleElement()
                .satisfies(line -> assertThat(line.right().itemRef()).isEqualTo("00101"));
    }

    /**
     * <b>이름 자리에 코드가 나오면 안 된다.</b>
     *
     * <p>「U-6668d23b · +11」 만 보고는 무슨 물건인지 알 수 없고, 알 수 없는 줄은 손대지 않게
     * 된다. 그리고 몇 개가 합쳐진 값인지도 보여야 한다 — 「4↔4건」 과 「1↔2건」 은 전혀 다른
     * 상태인데 합계만 보면 둘 다 숫자 하나다.
     */
    @Test
    @WithMockUser
    @DisplayName("대조 결과가 코드가 아니라 이름을 보여주고, 몇 개가 합쳐졌는지 말한다")
    void 결과가_이름을_보여준다() throws Exception {
        linkedUnit();
        // 두 번 돌려 «지금 볼 것» 으로 승격시킨다 — 처음 보이는 차이는 지켜볼 것에 머문다.
        engine.run(definition.getId());
        ReconcileRun run = engine.run(definition.getId());

        mockMvc.perform(get("/reconcile/runs/{id}", run.getId()).locale(Locale.KOREAN))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                .andExpect(content().string(Matchers.containsString("클래식 850g")))
                .andExpect(content().string(Matchers.containsString("4↔4건")))
                .andExpect(content().string(Matchers.containsString("뜯어보기")));
    }

    /** 펼치면 그 자리에서 로트별 내역이 나온다. */
    @Test
    @WithMockUser
    @DisplayName("뜯어보면 로트별 좌·우와 차이가 그 자리에 나온다")
    void 결과에서_뜯어본다() throws Exception {
        ReconcileUnit unit = linkedUnit();
        ReconcileRun run = engine.run(definition.getId());

        mockMvc.perform(get("/reconcile/runs/{id}", run.getId())
                        .locale(Locale.KOREAN)
                        .param("expand", unit.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(content().string(Matchers.containsString("품목별로 뜯어보기")))
                .andExpect(content().string(Matchers.containsString("C850P_27.03.16")))
                .andExpect(content().string(Matchers.containsString("00090")))
                .andExpect(content().string(Matchers.containsString("3건이 어긋납니다")));
    }

    /**
     * <b>로트 넷을 묶었는데 이름이 특정 로트 날짜면 안 된다.</b>
     *
     * <p>「클래식 850g (27.03.16)」 은 그 로트 하나의 이야기로 읽힌다. 실제로는 네 로트의 합이다.
     */
    @Test
    @DisplayName("여럿을 이으면 공통 부분만 이름으로 쓴다")
    void 공통_부분만_이름으로() {
        // 이 시험은 괄호 규칙이 있어야 성립한다. 기본 규칙은 꺼진 채로 설치된다.
        bracketRule();
        MatchBoard.Row row = board.load(TENANT, Pairing.ofSources(erp, wms), MatchBoard.Tab.PAIRED, null, 0)
                .rows().getFirst();

        assertThat(row.suggestedName())
                .as("첫 품목 이름을 그대로 쓰면 특정 로트 날짜가 전체를 대표하게 된다")
                .isEqualTo("클래식 850g");
    }

    /** 하나뿐이면 자를 것이 없다 — 그 품목의 이름이 곧 그 물건의 이름이다. */
    @Test
    @DisplayName("품목이 하나뿐이면 그 이름을 그대로 쓴다")
    void 하나면_그대로() {
        String other = "erp2-" + UUID.randomUUID().toString().substring(0, 6);
        String otherWms = "wms2-" + UUID.randomUUID().toString().substring(0, 6);
        snapshot(other, "X1", "코코아 227g (27.01.15)", "10");
        snapshot(otherWms, "Y1", "코코아 227g (27.01.15)", "8");

        MatchBoard.Row row = board.load(TENANT, Pairing.ofSources(other, otherWms), MatchBoard.Tab.PAIRED, null, 0)
                .rows().getFirst();

        assertThat(row.suggestedName()).isEqualTo("코코아 227g (27.01.15)");
    }

    /**
     * 이미 만들어진 물건의 옛 이름을 <b>담긴 품명으로 다시 지을 수 있다.</b>
     *
     * <p>규칙을 고쳐도 이미 만들어진 것은 옛 이름을 그대로 달고 있다. 하나씩 손으로 고치라고
     * 하면 아무도 안 고치고, 그러면 규칙을 고친 의미가 없다.
     */
    @Test
    @WithMockUser
    @DisplayName("옛 이름을 담긴 품명으로 다시 짓는다")
    void 이름을_다시_짓는다() throws Exception {
        ReconcileUnit unit = unitService.link(List.of(
                        new ReconcileUnitService.Pick(erp, "C850P_27.03.16", BigDecimal.ONE),
                        new ReconcileUnitService.Pick(erp, "C850P_27.04.16", BigDecimal.ONE),
                        new ReconcileUnitService.Pick(wms, "00090", BigDecimal.ONE),
                        new ReconcileUnitService.Pick(wms, "00091", BigDecimal.ONE)),
                "U-" + UUID.randomUUID().toString().substring(0, 8),
                "클래식 850g (27.03.16)", "EA");

        assertThat(naming.suggestions(TENANT, Pairing.ofSources(erp, wms)))
                .as("담긴 품명과 다른 이름은 다시 지을 후보로 보여야 한다")
                .anySatisfy(suggestion -> {
                    assertThat(suggestion.unitId()).isEqualTo(unit.getId());
                    assertThat(suggestion.suggestedName()).isEqualTo("클래식 850g");
                });

        mockMvc.perform(post("/reconcile/units/rename-suggested")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("definitionId", definition.getId().toString())
                        .param("units", unit.getId().toString()))
                .andExpect(status().is3xxRedirection());

        assertThat(unitService.activeUnits())
                .filteredOn(active -> active.getId().equals(unit.getId()))
                .singleElement()
                .satisfies(active -> assertThat(active.getName()).isEqualTo("클래식 850g"));
    }

    /** 이름은 사람이 정할 수 있어야 한다 — 자동으로 지은 것이 늘 옳지는 않다. */
    @Test
    @WithMockUser
    @DisplayName("이름을 손으로 고칠 수 있다")
    void 이름을_손으로_고친다() throws Exception {
        ReconcileUnit unit = linkedUnit();

        mockMvc.perform(post("/reconcile/units/{id}/rename", unit.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("definitionId", definition.getId().toString())
                        .param("name", "클래식 피넛버터 850g"))
                .andExpect(status().is3xxRedirection());

        assertThat(unitService.activeUnits())
                .filteredOn(active -> active.getId().equals(unit.getId()))
                .singleElement()
                .satisfies(active ->
                        assertThat(active.getName()).isEqualTo("클래식 피넛버터 850g"));
    }

    /**
     * <b>기준이 코드에 박혀 있으면 다른 구조의 자료에서는 이 화면이 통째로 쓸모없어진다.</b>
     *
     * <p>이 발주사 자료는 로트가 <b>품명 안에</b> 들어 있어 「품명이 닮은 것끼리」 로 잘 맞는다.
     * 그런데 그건 이 자료의 사정이지 이 프로그램의 성질이 아니다 — 로트를 별도 칸으로 주는
     * 시스템에서는 품명이 전부 같아 그 기준으로는 아무것도 못 가린다.
     *
     * <p>그래서 기준은 <b>표준 모델의 칸 목록</b>에서 나온다. 칸이 늘면 고를 것도 저절로 는다.
     */
    @Test
    @DisplayName("뜯어보기 기준을 표준 모델의 칸에서 고를 수 있다")
    void 기준을_고를_수_있다() {
        List<BreakdownAxis> axes = breakdowns.axes(TENANT);

        assertThat(axes).first().satisfies(axis ->
                assertThat(axis.kind()).isEqualTo(BreakdownAxis.Kind.NAME));
        assertThat(axes)
                .as("칸이 늘면 고를 것도 저절로 늘어야 한다 — 코드에 박아 두면 그럴 수 없다")
                .anySatisfy(axis -> assertThat(axis.fieldKey()).isEqualTo("lot_code"))
                .anySatisfy(axis -> assertThat(axis.fieldKey()).isEqualTo("warehouse_code"));
        assertThat(axes)
                .as("수량으로 묶으면 같은 수량끼리 모이는데 아무 뜻이 없다")
                .noneSatisfy(axis -> assertThat(axis.fieldKey()).isEqualTo("base_quantity"));
    }

    /**
     * <b>로트를 별도 칸으로 주는 시스템</b>에서도 뜯어볼 수 있어야 한다.
     *
     * <p>이런 자료는 품명이 전부 같으므로 「품명이 닮은 것끼리」 로는 짝을 가릴 수 없다.
     */
    @Test
    @DisplayName("로트가 별도 칸으로 오는 자료는 그 칸을 기준으로 짝짓는다")
    void 칸을_기준으로_짝짓는다() {
        String left = "erpL-" + UUID.randomUUID().toString().substring(0, 6);
        String right = "wmsL-" + UUID.randomUUID().toString().substring(0, 6);
        // 품명이 전부 같고 로트만 다르다 — 이름으로는 아무것도 못 가린다.
        lotSnapshot(left, "P1", "제품A", "L-01", "5");
        lotSnapshot(left, "P2", "제품A", "L-02", "7");
        lotSnapshot(right, "Q1", "제품A", "L-02", "7");
        lotSnapshot(right, "Q2", "제품A", "L-01", "3");

        ReconcileUnit unit = unitService.link(List.of(
                        new ReconcileUnitService.Pick(left, "P1", BigDecimal.ONE),
                        new ReconcileUnitService.Pick(left, "P2", BigDecimal.ONE),
                        new ReconcileUnitService.Pick(right, "Q1", BigDecimal.ONE),
                        new ReconcileUnitService.Pick(right, "Q2", BigDecimal.ONE)),
                "U-" + UUID.randomUUID().toString().substring(0, 8), "제품A", "EA");

        BreakdownAxis byLot = breakdowns.axisOf(TENANT, "FIELD:lot_code");
        UnitBreakdown.Breakdown result = breakdowns.of(TENANT, unit.getId(), Pairing.ofSources(left, right),
                UnitBreakdown.At.of(baseAt, baseAt, baseAt), byLot);

        assertThat(result.lines()).hasSize(2);
        assertThat(result.orphans()).isZero();
        assertThat(result.lines())
                .as("같은 로트끼리 맺어야 «무엇과 무엇이» 다른지 보인다")
                .allSatisfy(line -> assertThat(line.left().axisKey())
                        .isEqualTo(line.right().axisKey()));
        assertThat(result.lines())
                .filteredOn(line -> line.axisKey().equals("L-01"))
                .singleElement()
                .satisfies(line -> assertThat(line.diff()).isEqualByComparingTo("2"));
    }

    /**
     * 고른 기준이 <b>그 자료에 없으면 말해야 한다.</b>
     *
     * <p>조용히 「전부 짝 없음」 을 보여주면 사람은 자료가 잘못된 줄 알지, 기준을 잘못 골랐다고는
     * 생각하지 못한다. 실제로 이 발주사의 물류 쪽에는 창고 칸이 비어 있다.
     */
    @Test
    @DisplayName("고른 기준의 값이 한쪽에 없으면 그 사실을 알린다")
    void 기준이_안_맞으면_말한다() {
        ReconcileUnit unit = linkedUnit();

        UnitBreakdown.Breakdown result = breakdowns.of(TENANT, unit.getId(), Pairing.ofSources(erp, wms),
                UnitBreakdown.At.of(baseAt, baseAt, baseAt), breakdowns.axisOf(TENANT, "FIELD:lot_code"));

        assertThat(result.axisUnusable())
                .as("말하지 않으면 사람은 자료가 잘못된 줄 안다")
                .isTrue();
    }

    /** 견줄 근거가 없으면 <b>억지로 맺지 않는다.</b> 못 맺는 것이 잘못 맺는 것보다 낫다. */
    @Test
    @DisplayName("짝짓지 않기로 하면 양쪽을 그대로 늘어놓는다")
    void 짝짓지_않는다() {
        ReconcileUnit unit = linkedUnit();

        UnitBreakdown.Breakdown result = breakdowns.of(TENANT, unit.getId(), Pairing.ofSources(erp, wms),
                UnitBreakdown.At.of(baseAt, baseAt, baseAt), BreakdownAxis.none());

        assertThat(result.lines()).hasSize(8);
        assertThat(result.lines()).noneSatisfy(line -> assertThat(line.paired()).isTrue());
    }

    private void lotSnapshot(String source, String itemRef, String name, String lot, String qty) {
        var at = baseAt.atOffset(ZoneOffset.UTC);
        jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source, warehouse_code, lot_code,
                             quantity, base_quantity, base_unit, raw_item_name,
                             created_at, updated_at)
                        VALUES (:id, :tenant, :item, :at, :source, '', :lot,
                                :qty, :qty, 'EA', :name, :at, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("item", itemRef)
                .param("at", at)
                .param("source", source)
                .param("lot", lot)
                .param("qty", new BigDecimal(qty))
                .param("name", name)
                .update();
    }

    /**
     * <b>화면이 「이걸 하면 무엇이 어떻게 되는지」 를 말해야 한다.</b>
     *
     * <p>말하지 않으면 버튼 이름만 늘어날 뿐 사람은 무엇을 하는 중인지 모른다. 「묶기」·
     * 「1↔2건」 이 전부 뜻 없는 글자로 보인 이유가 그것이다. 그리고 지어낸 예시가 아니라
     * <b>자기 화면에 실제로 있는 줄</b>이어야 「아 저게 그거구나」 로 이어진다.
     */
    @Test
    @WithMockUser
    @DisplayName("안내가 자기 자료로 «묶으면 어떻게 계산되는지» 를 보여준다")
    void 안내가_자기_자료로_설명한다() throws Exception {
        linkedUnit();

        mockMvc.perform(get("/reconcile/guide").locale(Locale.KOREAN))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                .andExpect(content().string(Matchers.containsString("묶음 안의 수량을 시스템별로 각각 더해서")))
                // 지어낸 예시가 아니라 실제로 담긴 줄이어야 한다
                .andExpect(content().string(Matchers.containsString("C850P_27.03.16")))
                // 버튼 이름이 무엇을 하는지 적혀 있어야 한다
                .andExpect(content().string(Matchers.containsString("묶음 풀기")))
                .andExpect(content().string(Matchers.containsString("이 묶음에 넣기")));
    }

    /**
     * <b>조절할 수 있는 것이 한 자리에 보여야 한다.</b>
     *
     * <p>이름 규칙은 별도 화면, 뜯어보기 기준은 결과 안쪽, 몇 개로 칠지는 품목 줄 안쪽에
     * 흩어져 있었다. 무엇을 조절할 수 있는지 보여주는 자리가 없으면 자기 자료에 안 맞아도
     * 그냥 참고 쓰게 된다.
     */
    @Test
    @WithMockUser
    @DisplayName("맞추는 방식이 조절 항목과 지금 값을 함께 보여준다")
    void 맞추는_방식이_한_자리에() throws Exception {
        linkedUnit();

        mockMvc.perform(get("/reconcile/method").locale(Locale.KOREAN))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                .andExpect(content().string(Matchers.containsString("이름 다듬기")))
                .andExpect(content().string(Matchers.containsString("묶음 이름")))
                .andExpect(content().string(Matchers.containsString("뜯어보기 기준")))
                .andExpect(content().string(Matchers.containsString("몇 개로 칠지")));
    }

    /**
     * 묶음 이름 규칙이 <b>실제로 적용된다.</b>
     *
     * <p>「공통 부분만」 이 모든 자료에 옳지는 않다 — 품명이 아예 다른 두 시스템에서는 공통
     * 부분이 토막으로 남는다. 그래서 고를 수 있어야 하고, 고른 것이 실제로 먹어야 한다.
     */
    @Test
    @WithMockUser
    @DisplayName("고른 묶음 이름 규칙이 실제로 적용된다")
    void 이름_규칙이_적용된다() throws Exception {
        mockMvc.perform(post("/reconcile/{id}/unit-name-rule", definition.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("rule", "FIRST_ITEM"))
                .andExpect(status().is3xxRedirection());

        String key = board.load(TENANT, Pairing.ofSources(erp, wms), MatchBoard.Tab.PAIRED, null, 0)
                .rows().getFirst().key();
        mockMvc.perform(post("/reconcile/units/link")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .locale(Locale.KOREAN)
                        .param("definitionId", definition.getId().toString())
                        .param("row", key))
                .andExpect(status().is3xxRedirection());

        assertThat(unitService.activeUnits())
                .as("첫 품목 이름 그대로를 골랐으면 로트 날짜가 붙은 이름이 나와야 한다")
                .anySatisfy(unit ->
                        assertThat(unit.getName()).isEqualTo("클래식 850g (27.03.16)"));
    }

    /**
     * 회차가 <b>어느 시각의 자료를 봤는지</b> 남는다.
     *
     * <p>남지 않으면 나중에 뜯어볼 때 지금 담긴 최신 재고로 계산하게 되고, 그 사이 수집이 한
     * 번 더 돌았으면 화면에 적힌 합계와 뜯어본 내역의 합이 어긋난다. 사람은 둘 중 무엇이
     * 맞는지 알 방법이 없다.
     */
    @Test
    @DisplayName("회차가 원천별 기준 시각을 남긴다")
    void 원천별_기준시각을_남긴다() {
        linkedUnit();
        ReconcileRun run = engine.run(definition.getId());

        assertThat(run.getLeftBaseAt())
                .as("남기지 않으면 그 회차가 본 자료를 다시 불러올 수 없다")
                .isNotNull();
        assertThat(run.getRightBaseAt()).isNotNull();
    }

    /**
     * <b>합계와 뜯어보기가 같은 수를 낸다.</b>
     *
     * <p>엔진은 정의가 고른 창고로 좁혀 합산하는데 뜯어보기가 전 창고를 더하면, 결과 화면에서
     * 줄의 합계와 그 <b>바로 아래</b> 상세가 다른 수를 말한다. 두 값이 붙어 있어 어긋남이 즉시
     * 눈에 띄고, 어느 쪽을 믿을지 알 방법이 없다.
     */
    @Test
    @DisplayName("창고를 고르면 뜯어보기도 그 창고만 더한다")
    void 뜯어보기가_창고를_따른다() {
        snapshot(erp, "P-1", "제품A", "100", "W-CONSIGN");
        snapshot(erp, "P-1", "제품A", "30", "W-OFFICE");
        snapshot(wms, "Q-1", "제품A", "100");

        ReconcileUnit unit = unitService.link(List.of(
                new ReconcileUnitService.Pick(erp, "P-1", BigDecimal.ONE),
                new ReconcileUnitService.Pick(wms, "Q-1", BigDecimal.ONE)),
                "U-WH-" + UUID.randomUUID().toString().substring(0, 6), "제품A", "EA");

        Pairing all = Pairing.ofSources(erp, wms);
        Pairing consigned = new Pairing(erp, wms,
                new WarehouseScope(List.of("W-CONSIGN")), WarehouseScope.all(), null);

        var whole = breakdowns.of(TENANT, unit.getId(), all,
                UnitBreakdown.At.of(baseAt, baseAt, baseAt), BreakdownAxis.byName());
        var scoped = breakdowns.of(TENANT, unit.getId(), consigned,
                UnitBreakdown.At.of(baseAt, baseAt, baseAt), BreakdownAxis.byName());

        assertThat(whole.lines()).singleElement().satisfies(line ->
                assertThat(line.leftQuantity())
                        .as("창고를 안 고르면 위탁 100 + 사무실 30 을 더한다")
                        .isEqualByComparingTo("130"));

        assertThat(scoped.lines()).singleElement().satisfies(line -> {
            assertThat(line.leftQuantity())
                    .as("위탁 창고만 고르면 그 창고 수량만 더한다 — 엔진 합계와 같아야 한다")
                    .isEqualByComparingTo("100");
            assertThat(line.diff())
                    .as("좁히면 어긋남이 사라진다")
                    .isEqualByComparingTo("0");
        });
    }

    /**
     * 고른 창고 밖에 있는 품목은 <b>「없음」 이지 「0개」 가 아니다.</b>
     *
     * <p>0 으로 그리면 「재고가 0 이다」 라는 없는 이야기가 된다 — 실제로는 그 창고에서 안 보기로
     * 한 것뿐이다. 그리고 줄에서 아예 빼 버리면 그 품목을 빼거나 옮길 자리도 함께 사라진다.
     */
    @Test
    @DisplayName("고른 창고 밖 품목은 줄에 남되 「없음」 으로 그려지고 이름을 잃지 않는다")
    void 범위_밖_품목은_없음으로() {
        snapshot(erp, "P-2", "제품B", "50", "W-OFFICE");
        snapshot(wms, "Q-2", "제품B", "50");

        ReconcileUnit unit = unitService.link(List.of(
                new ReconcileUnitService.Pick(erp, "P-2", BigDecimal.ONE),
                new ReconcileUnitService.Pick(wms, "Q-2", BigDecimal.ONE)),
                "U-OUT-" + UUID.randomUUID().toString().substring(0, 6), "제품B", "EA");

        var scoped = breakdowns.of(TENANT, unit.getId(),
                new Pairing(erp, wms, new WarehouseScope(List.of("W-CONSIGN")),
                        WarehouseScope.all(), null),
                UnitBreakdown.At.of(baseAt, baseAt, baseAt), BreakdownAxis.byName());

        assertThat(scoped.lines())
                .as("범위 밖이라고 줄에서 빼면 그 품목을 손댈 자리가 사라진다")
                .isNotEmpty()
                .anySatisfy(line -> {
                    assertThat(line.leftText())
                            .as("「0」 으로 그리면 재고가 0 이라는 없는 이야기가 된다")
                            .isEqualTo("—");
                    assertThat(line.label())
                            .as("이름은 「무엇」 이라 창고와 무관하다 — 품목코드가 나오면 안 된다")
                            .isEqualTo("제품B");
                });
    }
}
