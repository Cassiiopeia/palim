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
                .andExpect(content().string(containsString("재고 대조")));
    }

    @Test
    @WithMockUser
    @DisplayName("맞춰 본 기록이 그려진다")
    void 실행_이력이_그려진다() throws Exception {
        ReconcileRun run = confirmedRun();

        mockMvc.perform(get("/reconcile/{id}", run.getDefinitionId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("맞춰 본 기록")));
    }

    /**
     * 확인 전과 후를 «분명히» 갈라야 한다. 제안 상태를 확정처럼 보이게 하면 아무도 확인하지
     * 않고, 그러면 확인 단계를 둔 의미가 없어진다.
     */
    @Test
    @WithMockUser
    @DisplayName("품목 잇기 화면이 확인 대기와 정해 둔 것을 갈라 보여준다")
    void 품목_잇기_화면이_그려진다() throws Exception {
        ReconcileUnit unit = unitService.create(
                "UNIT-" + UUID.randomUUID().toString().substring(0, 8), "제품B 100g", "EA");
        // 제안만 하고 확정하지 않는다 — 화면이 이것을 «확인해 주세요» 로 보여줘야 한다.
        unitService.propose(unit.getId(), erp, "E-PENDING", BigDecimal.ONE);

        mockMvc.perform(get("/reconcile/units"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("확인해 주세요")))
                .andExpect(content().string(containsString("정해 둔 품목")))
                // 틀린 채로 두면 어떻게 되는지 말해 줘야 사람이 실제로 확인한다
                .andExpect(content().string(containsString("엉뚱한 재고를 합쳐")));
    }
}
