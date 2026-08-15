package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import kr.suhsaechan.palim.common.BaseAtGranularity;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

/**
 * <b>영영 안 맞는 대조</b>를 만들지 못하게 한다.
 *
 * <p>담는 눈금과 견주는 눈금은 다른 값이다. 하루에 한 번 담는 원천은 기준 시각이 늘 자정에
 * 찍히므로, 시간 단위로 견주기로 하면 상대가 그 칸에 있을 수 없다. 그러면 대조는 매일
 * 「기준 시각이 다릅니다」 만 남기고 <b>사람은 무엇이 잘못됐는지 알 길이 없다</b> — 설정은
 * 저장됐고 실행도 됐으니 화면 어디에도 원인이 없다.
 *
 * <p>그래서 저장 시점에 막고, 무엇을 해야 하는지까지 말한다.
 */
@AutoConfigureMockMvc
class ReconcileGranularityIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ConnectorRepository connectors;
    @Autowired private TargetModelRepository targetModels;
    @Autowired private ReconcileDefinitionRepository definitions;

    private ReconcileDefinition definition;
    private Connector left;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        TargetModel model = targetModels.findByTenantIdAndCode(TENANT, "std_stock_snapshot")
                .orElseThrow();
        left = connector(model, "erp");
        Connector right = connector(model, "wms");
        definition = definitions.save(ReconcileDefinition.of(TENANT,
                "gran-" + UUID.randomUUID().toString().substring(0, 6), "전산 대 물류",
                left.getCode(), right.getCode(), "base_quantity",
                BigDecimal.ZERO, null));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Connector connector(TargetModel model, String prefix) {
        return connectors.save(Connector.of(TENANT,
                prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                prefix, model.getId(), SourceType.HTTP_API, "EA"));
    }

    /** 기본값은 하루다 — 지금까지의 동작 그대로라 이미 만들어 둔 대조의 뜻이 바뀌지 않는다. */
    @Test
    @DisplayName("새 대조는 하루 눈금으로 시작한다")
    void 기본은_하루() {
        assertThat(definitions.findById(definition.getId()).orElseThrow().granularityOrDay())
                .isEqualTo(BaseAtGranularity.DAY);
    }

    @Test
    @WithMockUser
    @DisplayName("담는 눈금보다 잘게 잡으면 막고 무엇을 해야 하는지 말한다")
    void 잘게_잡으면_막는다() throws Exception {
        // 양쪽 다 하루에 한 번 담는 상태 — 새 커넥터의 기본값이다
        mockMvc.perform(post("/reconcile/{id}/granularity", definition.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("granularity", "HOUR"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", Matchers.allOf(
                        // 어느 연동이 걸렸는지 알아야 사람이 고칠 곳을 안다
                        Matchers.containsString(left.getCode()),
                        // 「안 됩니다」 만으로는 무엇을 해야 하는지 알 수 없다
                        Matchers.containsString("담는 눈금을 먼저"))));

        assertThat(definitions.findById(definition.getId()).orElseThrow().granularityOrDay())
                .as("막았다면 값도 그대로여야 한다")
                .isEqualTo(BaseAtGranularity.DAY);
    }

    /** 담는 쪽을 먼저 촘촘하게 바꾸면 견주기도 촘촘해질 수 있다. 막다른 길이 아니어야 한다. */
    @Test
    @WithMockUser
    @DisplayName("담는 눈금을 먼저 촘촘하게 하면 견주기도 촘촘해진다")
    void 담는_쪽을_바꾸면_열린다() throws Exception {
        for (Connector connector : connectors.findByTenantIdOrderByName(TENANT)) {
            connector.changeBaseAtGranularity(BaseAtGranularity.TEN_MINUTES);
            connectors.save(connector);
        }

        mockMvc.perform(post("/reconcile/{id}/granularity", definition.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("granularity", "HOUR"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        assertThat(definitions.findById(definition.getId()).orElseThrow().granularityOrDay())
                .isEqualTo(BaseAtGranularity.HOUR);
    }

    /**
     * 화면에 이 값이 보여야 「왜 안 맞지」 가 풀린다. 값이 코드에만 있으면 사람은 서버 로그를
     * 뒤져야 하는데, 그럴 수 있는 사람이 쓰는 화면이 아니다.
     */
    @Test
    @WithMockUser
    @DisplayName("대조 화면이 견줄 눈금을 보여준다")
    void 화면에_보인다() throws Exception {
        mockMvc.perform(get("/reconcile/{id}", definition.getId()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(Matchers.containsString("견줄 눈금")));
    }
}
