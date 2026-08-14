package kr.suhsaechan.palim.integration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.connector.run.ConnectorRun;
import kr.suhsaechan.palim.connector.run.ConnectorRunRepository;
import kr.suhsaechan.palim.connector.run.RunMode;
import kr.suhsaechan.palim.connector.run.RunTrigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 실행 이력·실행 결과 화면이 <b>자료가 있을 때</b> 끝까지 그려지는가.
 *
 * <p>화면을 비어 있는 상태로만 열어 보는 것으로는 부족하다. 목록이 비면 행을 그리는 부분이
 * <b>한 번도 실행되지 않아</b> 그 안의 표현식이 검사되지 않는다. 정작 사람이 보는 화면은
 * 언제나 행이 있는 쪽이다.
 *
 * <p>상태를 골고루 섞는 이유는 행마다 다른 가지를 타기 때문이다 — 성공·일부 실패·실패·되돌림이
 * 각각 다르게 그려진다. 하나만 넣으면 나머지 가지는 여전히 열어 보지 않은 셈이다.
 */
@AutoConfigureMockMvc
class RunHistoryScreenRenderIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private TargetModelRepository targetModelRepository;
    @Autowired private ConnectorRunRepository runRepository;

    private Connector connector;
    private ConnectorRun succeeded;
    private ConnectorRun rolledBack;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT, "std_stock_snapshot").orElseThrow();
        connector = connectorRepository.save(Connector.of(
                TENANT, "runs-" + UUID.randomUUID().toString().substring(0, 8),
                "실행 이력 확인용", model.getId(), SourceType.HTTP_API, "EA"));

        succeeded = save(RunMode.LIVE, run -> run.finish(120, 120, 0));
        save(RunMode.LIVE, run -> run.finish(80, 77, 3));          // 일부 실패
        save(RunMode.TEST, run -> run.fail("원천 서버가 응답하지 않습니다"));
        rolledBack = save(RunMode.LIVE, run -> {
            run.finish(50, 50, 0);
            run.markRolledBack();
        });
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ConnectorRun save(RunMode mode, java.util.function.Consumer<ConnectorRun> finish) {
        ConnectorRun run = ConnectorRun.start(
                TENANT, connector.getId(), UUID.randomUUID(), 1, mode, RunTrigger.MANUAL);
        finish.accept(run);
        return runRepository.save(run);
    }

    @Test
    @WithMockUser
    @DisplayName("실행 이력이 여러 건 있어도 끝까지 그려진다")
    void 실행_이력이_그려진다() throws Exception {
        mockMvc.perform(get("/connectors/{id}/runs", connector.getId()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered());
    }

    /**
     * 되돌린 실행은 «되돌리기» 를 다시 걸 수 없어야 한다. 그 판단이 행 안에서 이뤄지므로
     * 되돌린 것이 <b>가장 최근</b> 일 때를 따로 열어 본다 — 첫 행만 되돌릴 수 있기 때문이다.
     */
    @Test
    @WithMockUser
    @DisplayName("되돌린 실행이 맨 위에 있어도 그려진다")
    void 되돌린_실행이_맨_위여도_그려진다() throws Exception {
        mockMvc.perform(get("/connectors/{id}/runs", connector.getId()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered());

        // 되돌린 실행의 상세도 열린다
        mockMvc.perform(get("/connectors/{id}/runs/{runId}",
                        connector.getId(), rolledBack.getId()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered());
    }

    @Test
    @WithMockUser
    @DisplayName("실행 결과 화면이 끝까지 그려진다")
    void 실행_결과가_그려진다() throws Exception {
        mockMvc.perform(get("/connectors/{id}/runs/{runId}",
                        connector.getId(), succeeded.getId()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered());
    }

    /**
     * 주소를 직접 고쳐 없는 실행을 열면 화면이 «없다» 고 말해야 한다. 여기서 터지면 사용자는
     * 오타 하나로 오류 화면을 보게 된다.
     */
    @Test
    @WithMockUser
    @DisplayName("없는 실행을 열어도 화면이 그려진다")
    void 없는_실행도_그려진다() throws Exception {
        mockMvc.perform(get("/connectors/{id}/runs/{runId}",
                        connector.getId(), UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered());
    }

    @Test
    @WithMockUser
    @DisplayName("목록이 연결과 칸 맞추기를 나란히 보여준다")
    void 커넥터_목록이_그려진다() throws Exception {
        mockMvc.perform(get("/connectors"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("실행 이력 확인용")))
                // 연결만 하면 끝난 것처럼 보이던 문제를 이 두 칸이 막는다
                .andExpect(content().string(containsString("연결")))
                .andExpect(content().string(containsString("칸 맞추기")))
                .andExpect(RenderAssertions.fullyRendered());
    }
}
