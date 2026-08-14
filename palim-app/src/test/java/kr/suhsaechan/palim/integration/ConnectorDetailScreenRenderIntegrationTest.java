package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.web.connector.ConnectorAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 한 시스템의 상태가 <b>한 자리에</b> 모이는가.
 *
 * <p>지금은 연결·칸 맞추기·이력이 흩어져 있어서 「이 시스템만 확인하고 싶다」가 갈 곳이 없다.
 * 비밀번호만 바꾸고 싶은데 긴 흐름을 다시 타야 한다.
 */
@AutoConfigureMockMvc
class ConnectorDetailScreenRenderIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = ConnectorAdminService.DEFAULT_TENANT;

    @Autowired private MockMvc mockMvc;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private TargetModelRepository targetModelRepository;

    private Connector connector;

    @BeforeEach
    void setUp() {
        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT, "std_stock_snapshot").orElseThrow();
        connector = connectorRepository.save(Connector.of(
                TENANT, "detail-" + UUID.randomUUID().toString().substring(0, 8),
                "상세 확인용", model.getId(), SourceType.HTTP_API, "EA"));
    }

    @Test
    @WithMockUser
    @DisplayName("한 시스템의 상태가 한 자리에 모인다")
    void 상세가_그려진다() throws Exception {
        mockMvc.perform(get("/connectors/{id}", connector.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("상세 확인용")))
                .andExpect(content().string(containsString("연결")))
                .andExpect(content().string(containsString("칸 맞추기")))
                .andExpect(content().string(containsString("자동 수집")))
                // 적재가 단위 때문에 막혔을 때 풀 화면으로 갈 수 있어야 한다
                .andExpect(content().string(containsString("단위 환산")))
                .andExpect(RenderAssertions.fullyRendered());
    }

    /**
     * 자동 수집 시각을 넣을 자리가 지금 어디에도 없어 스케줄러가 영원히 건너뛴다. 사장님은
     * cron 을 모르므로 <b>시각만</b> 고르게 하고 표현식은 화면이 만든다.
     */
    @Test
    @WithMockUser
    @DisplayName("몇 시에 가져올지 정할 수 있다")
    void 수집_시각을_정한다() throws Exception {
        mockMvc.perform(post("/connectors/{id}/schedule", connector.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("hour", "6")
                        .param("minute", "30"))
                .andExpect(status().is3xxRedirection());

        Connector saved = connectorRepository.findById(connector.getId()).orElseThrow();
        assertThat(saved.getScheduleCron())
                .as("스케줄러가 읽는 것은 cron 이다 — 화면이 시각을 표현식으로 옮겨야 한다")
                .isEqualTo("0 30 6 * * *");
    }

    /** 자동으로 안 가져오게 되돌릴 수도 있어야 한다. 켜기만 되고 끄기가 없으면 갇힌다. */
    @Test
    @WithMockUser
    @DisplayName("자동 수집을 끌 수 있다")
    void 수집을_끈다() throws Exception {
        mockMvc.perform(post("/connectors/{id}/schedule", connector.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("hour", "")
                        .param("minute", ""))
                .andExpect(status().is3xxRedirection());

        assertThat(connectorRepository.findById(connector.getId()).orElseThrow()
                .getScheduleCron()).isNull();
    }

    /**
     * 범위 밖 시각이 그대로 cron 으로 저장되면 {@code CronExpression.parse} 가 매분 터진다.
     * {@code ConnectorScheduler} 가 커넥터별로 예외를 잡아 스케줄러 전체는 죽지 않지만, 그
     * 커넥터만 영구히 건너뛰어지고 로그에 매분 ERROR 가 쌓인다 — 저장 전에 막아야 한다.
     */
    @Test
    @WithMockUser
    @DisplayName("범위를 벗어난 시각은 저장되지 않는다")
    void 범위_밖_시각은_저장되지_않는다() throws Exception {
        mockMvc.perform(post("/connectors/{id}/schedule", connector.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("hour", "25")
                        .param("minute", "0"))
                .andExpect(status().is3xxRedirection());

        assertThat(connectorRepository.findById(connector.getId()).orElseThrow()
                .getScheduleCron())
                .as("리다이렉트만 봐서는 조용히 저장돼도 통과한다 — cron 이 비어 있어야 한다")
                .isNull();
    }
}
