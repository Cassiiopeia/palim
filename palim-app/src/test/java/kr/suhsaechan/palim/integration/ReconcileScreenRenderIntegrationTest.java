package kr.suhsaechan.palim.integration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.engine.ReconcileEngine;
import kr.suhsaechan.palim.reconcile.run.ReconcileRun;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnit;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitService;
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
 * 대조 결과 화면이 <b>실제로 그려지는가</b>.
 *
 * <p>템플릿 표현식 오류는 컴파일에 걸리지 않는다. 화면을 여는 순간 터지고, 그때는 이미 배포된
 * 뒤다.
 *
 * <p>내용까지 확인하는 이유는 이 화면의 존재 이유가 <b>「지금 볼 것」과 「지켜볼 것」을 가르는
 * 데</b> 있기 때문이다. 둘이 섞여 그려지면 화면이 있으나 마나다.
 */
@AutoConfigureMockMvc
class ReconcileScreenRenderIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ReconcileEngine engine;
    @Autowired private ReconcileUnitService unitService;
    @Autowired private ReconcileDefinitionRepository definitions;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private TargetModelRepository targetModelRepository;

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

    private void snapshot(String source, String itemRef, String qty) {
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
                .param("at", baseAt.atOffset(ZoneOffset.UTC))
                .param("source", source)
                .param("qty", new BigDecimal(qty))
                .param("name", "제품 " + itemRef)
                .update();
    }

    /** 두 번 돌려 차이를 확정 상태까지 올린다 — 화면이 갈라 보여주는지 확인하려면 필요하다. */
    private ReconcileRun confirmedRun() {
        ReconcileUnit unit = unitService.create(
                "UNIT-" + UUID.randomUUID().toString().substring(0, 8), "제품A 227g", "EA");
        String erpItem = "E-" + UUID.randomUUID().toString().substring(0, 6);
        String wmsItem = "W-" + UUID.randomUUID().toString().substring(0, 6);
        unitService.confirm(unitService.propose(
                unit.getId(), erp, erpItem, BigDecimal.ONE).getId());
        unitService.confirm(unitService.propose(
                unit.getId(), wms, wmsItem, BigDecimal.ONE).getId());

        snapshot(erp, erpItem, "9451");
        snapshot(wms, wmsItem, "9000");
        snapshot(erp, "ORPHAN-" + UUID.randomUUID().toString().substring(0, 6), "7");

        ReconcileDefinition definition = definitions.save(ReconcileDefinition.of(TENANT,
                "DEF-" + UUID.randomUUID().toString().substring(0, 8), "전산 대 물류",
                erp, wms, "base_quantity", BigDecimal.ZERO, null));

        engine.run(definition.getId());
        return engine.run(definition.getId());
    }

    @Test
    @WithMockUser
    @DisplayName("결과 화면이 지금 볼 것과 지켜볼 것을 갈라 보여준다")
    void 결과_화면이_그려진다() throws Exception {
        ReconcileRun run = confirmedRun();

        mockMvc.perform(get("/reconcile/runs/{runId}", run.getId()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                // 지금 손댈 것과 지켜볼 것을 섞으면 둘 다 안 보게 된다
                .andExpect(content().string(containsString("지금 볼 것")))
                // 미매칭은 «재고를 맞출 것» 이 아니라 «품목을 이을 것» 이다
                .andExpect(content().string(containsString("아직 안 이어진 품목")))
                // 숫자는 자릿수를 맞춰 눈으로 비교할 수 있어야 한다
                .andExpect(content().string(containsString("9,451")))
                // 어느 쪽이 많은지는 시스템 이름으로 말해야 무엇을 볼지 안다
                .andExpect(content().string(containsString("쪽이")));
    }

    @Test
    @WithMockUser
    @DisplayName("대조 목록이 그려진다")
    void 목록이_그려진다() throws Exception {
        confirmedRun();

        mockMvc.perform(get("/reconcile"))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                // 사이드바 메뉴와 같은 이름이어야 «메뉴에서 본 그 화면» 이 이어진다
                .andExpect(content().string(containsString("대조 결과")))
                .andExpect(RenderAssertions.fullyRendered());
    }

    @Test
    @WithMockUser
    @DisplayName("맞춰 본 기록이 그려진다")
    void 실행_이력이_그려진다() throws Exception {
        ReconcileRun run = confirmedRun();

        mockMvc.perform(get("/reconcile/{id}", run.getDefinitionId()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(content().string(containsString("맞춰 본 기록")));
    }

    /**
     * 품목 잇기 화면이 <b>한 줄에 좌·우와 차이를</b> 놓는가.
     *
     * <p>예전 화면은 좌·우를 한 칸에 세로로 쌓아 무엇이 무엇과 짝인지 볼 수 없었고, 잇기 전에
     * 양쪽 수량을 견줄 수도 없었다. 대조를 하러 온 사람에게 대조할 수 없는 화면을 준 것이다.
     *
     * <p>아직 확인하지 않은 물건은 그 사실이 보여야 한다 — 확인 안 된 것이 확인된 것처럼
     * 보이면 아무도 확인하지 않는다.
     */
    @Test
    @WithMockUser
    @DisplayName("품목 잇기 화면이 좌·우와 차이를 한 줄에 놓고, 확인 대기를 드러낸다")
    void 품목_잇기_화면이_그려진다() throws Exception {
        String erpItem = "E-" + UUID.randomUUID().toString().substring(0, 6);
        String wmsItem = "W-" + UUID.randomUUID().toString().substring(0, 6);
        snapshot(erp, erpItem, "120");
        snapshot(wms, wmsItem, "100");

        ReconcileDefinition definition = definitions.save(ReconcileDefinition.of(TENANT,
                "DEF-" + UUID.randomUUID().toString().substring(0, 8), "전산 대 물류",
                erp, wms, "base_quantity", BigDecimal.ZERO, null));

        ReconcileUnit unit = unitService.create(
                "UNIT-" + UUID.randomUUID().toString().substring(0, 8), "제품B 100g", "EA");
        // 확인하지 않은 채로 둔다 — 화면이 이것을 «확인 대기» 로 드러내야 한다.
        unitService.propose(unit.getId(), erp, erpItem, BigDecimal.ONE);
        unitService.propose(unit.getId(), wms, wmsItem, BigDecimal.ONE);

        mockMvc.perform(get("/reconcile/units")
                        .param("definitionId", definition.getId().toString())
                        .param("tab", "LINKED"))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                .andExpect(content().string(containsString("품목 대조표")))
                .andExpect(content().string(containsString("제품B 100g")))
                // 차이가 그 줄에 있어야 사람이 잇기 전에 판단한다
                .andExpect(content().string(containsString("+20")))
                .andExpect(content().string(containsString("확인 대기")))
                // 되돌릴 길이 없으면 잘못 이은 것이 영영 남는다
                .andExpect(content().string(containsString("풀기")));
    }

    /**
     * 무엇과 무엇을 맞춰 볼지 <b>정할 수 있어야</b> 대조가 시작된다.
     *
     * <p>이 자리가 없어서 대조 정의가 운영에서 0행이었다. 시스템을 다 붙여도 화면은 늘 비어
     * 있었고, 매일 아침 도는 스케줄러는 빈 목록을 훑고 끝났다. 아무 일도 일어나지 않는 이유가
     * 이것이었다.
     */
    @Test
    @WithMockUser
    @DisplayName("시스템이 둘이면 맞춰 볼 대상을 정할 수 있다")
    void 대상을_정할_수_있다() throws Exception {
        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT, "std_stock_snapshot").orElseThrow();
        for (String name : new String[] {"전산 시스템", "물류 시스템"}) {
            connectorRepository.save(Connector.of(
                    TENANT, "def-" + UUID.randomUUID().toString().substring(0, 8),
                    name, model.getId(), SourceType.HTTP_API, "EA"));
        }

        mockMvc.perform(get("/reconcile"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("맞춰 볼 대상 정하기")))
                // 어느 쪽이 많은지가 곧 무엇을 할지라 좌·우를 나눠 묻는다
                .andExpect(content().string(containsString("전산 쪽")))
                .andExpect(content().string(containsString("실물 쪽")))
                .andExpect(RenderAssertions.fullyRendered());
    }
}
