package kr.suhsaechan.palim.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 품목 잇기 화면이 <b>어느 대조에서 찾는지</b>를 사람이 정하는가.
 *
 * <p>예전에는 코드가 활성 정의 중 <b>코드순 첫 번째</b>를 임의로 집었다. 그러면 정의 이름만
 * 바꿔도 후보 목록이 이유 없이 통째로 달라지는데, 화면 어디에도 「지금 어느 대조를 보고
 * 있는지」 가 없어 사람은 그 사실조차 모른다.
 *
 * <p>하나뿐이면 고를 것이 없으므로 그냥 쓴다 — 그건 짐작이 아니다. <b>여럿이면 묻는다.</b>
 *
 * <p>이어 둔 것 자체는 대조마다 갈리지 않는다({@code (tenant, source, item_ref)} 로 온 시스템이
 * 공유한다). 그래서 어느 대조를 골라 이었든 결과는 모든 대조에 함께 쓰인다 — 여기서 고르는
 * 것은 «찾아볼 범위» 일 뿐이고, 화면이 그 사실을 말해야 사람이 안심하고 고른다.
 */
@AutoConfigureMockMvc
class UnitCandidateScopeIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ReconcileDefinitionRepository definitions;

    private ReconcileDefinition first;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        // 이 화면은 «활성 정의 전체» 를 본다. 다른 시험이 남긴 것과 섞이지 않도록
        // 이 시험이 만드는 것만 켜 두고 나머지는 끈다.
        definitions.findByIsActiveTrueOrderByCode().forEach(existing -> {
            existing.deactivate();
            definitions.save(existing);
        });
        first = definition("전산 대 물류", "erp", "wms");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ReconcileDefinition definition(String name, String left, String right) {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        return definitions.save(ReconcileDefinition.of(TENANT,
                "def-" + tag, name, left + "-" + tag, right + "-" + tag,
                "base_quantity", BigDecimal.ZERO, null));
    }

    /** 하나뿐이면 고를 것이 없다. 묻는 것 자체가 방해다. */
    @Test
    @WithMockUser
    @DisplayName("대조가 하나뿐이면 묻지 않고 그것으로 찾는다")
    void 하나면_묻지_않는다() throws Exception {
        mockMvc.perform(get("/reconcile/units"))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                .andExpect(content().string(
                        Matchers.not(Matchers.containsString("어느 대조에서 찾을까요"))))
                // 하나뿐이어도 «어디에서 찾았는지» 는 밝힌다 — 나중에 늘어날 때 화면이
                // 말없이 달라지지 않게
                .andExpect(content().string(Matchers.containsString(first.getLeftSource())))
                .andExpect(content().string(Matchers.containsString("에 적힌 두 곳을 봅니다")));
    }

    /**
     * <b>여럿이면 코드가 고르지 않는다.</b> 임의로 하나를 집으면 사람은 왜 이 후보가 나왔는지
     * 알 수 없고, 정의 이름만 바꿔도 목록이 달라진다.
     */
    @Test
    @WithMockUser
    @DisplayName("대조가 여럿이면 어느 것으로 찾을지 묻는다")
    void 여럿이면_묻는다() throws Exception {
        definition("전산 대 쇼핑몰", "erp2", "mall");

        mockMvc.perform(get("/reconcile/units"))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(content().string(Matchers.containsString("어느 대조에서 찾을까요")))
                // 고르면 무엇이 달라지는지 말해야 안심하고 고른다
                .andExpect(content().string(
                        Matchers.containsString("이어 둔 것은 모든 대조가 함께 씁니다")))
                .andExpect(content().string(Matchers.containsString("전산 대 쇼핑몰")));
    }

    /** 고른 뒤에는 그것으로 찾고, 어디에서 찾았는지 밝힌다. */
    @Test
    @WithMockUser
    @DisplayName("고른 대조로 찾고 그 사실을 화면에 남긴다")
    void 고르면_그것으로_찾는다() throws Exception {
        ReconcileDefinition second = definition("전산 대 쇼핑몰", "erp2", "mall");

        mockMvc.perform(get("/reconcile/units").param("definitionId", second.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(content().string(Matchers.containsString(second.getLeftSource())))
                .andExpect(content().string(Matchers.containsString("에 적힌 두 곳을 봅니다")))
                // 되돌아갈 길이 있어야 막다른 길이 아니다
                .andExpect(content().string(Matchers.containsString("다른 대조 보기")));
    }

    /**
     * 없는 것을 가리키면 <b>말없이 빈 화면</b>이 되면 안 된다.
     *
     * <p>즐겨찾기로 남겨 둔 주소를 나중에 열었는데 그 대조가 지워졌을 때가 이 경우다.
     */
    @Test
    @WithMockUser
    @DisplayName("없는 대조를 가리키면 다시 고르게 한다")
    void 없는_것을_가리키면_다시_묻는다() throws Exception {
        mockMvc.perform(get("/reconcile/units")
                        .param("definitionId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(content().string(Matchers.containsString("어느 대조에서 찾을까요")));
    }
}
