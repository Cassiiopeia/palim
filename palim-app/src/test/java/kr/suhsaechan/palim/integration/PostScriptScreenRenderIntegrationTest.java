package kr.suhsaechan.palim.integration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import kr.suhsaechan.palim.connector.script.PostScript;
import kr.suhsaechan.palim.connector.script.PostScriptRepository;
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
 * 스크립트 화면이 <b>열리고, 실제로 동작하는가</b>.
 *
 * <p>「열린다」와 「동작한다」는 다르다. 이 화면은 끌어 옮기기와 예제 넣기를 스크립트로 하는데,
 * 그 스크립트가 화면 안에 박혀 있으면 보안 정책에 막혀 <b>한 줄도 돌지 않는다.</b> 그런데
 * 화면은 200 으로 멀쩡히 열려서, 눌러도 아무 일이 없는 상태를 사람 눈으로만 찾아야 한다 —
 * 실제로 두 화면이 그 상태로 배포됐다(07-DECISIONS 031).
 */
@AutoConfigureMockMvc
class PostScriptScreenRenderIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private TargetModelRepository targetModelRepository;
    @Autowired private PostScriptRepository scriptRepository;

    private Connector connector;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT, "std_stock_snapshot").orElseThrow();
        connector = connectorRepository.save(Connector.of(TENANT,
                "script-" + UUID.randomUUID().toString().substring(0, 8),
                "스크립트 화면 시험", model.getId(), SourceType.HTTP_API, "EA"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 처음 여는 사람에게 <b>빈 상자를 주지 않는다.</b>
     *
     * <p>파이썬을 모르는 사람이 빈 화면을 보면 거기서 멈춘다. 돌아가는 글 하나를 미리 넣어
     * 두고, 거기서 한 줄씩 고치게 한다.
     */
    @Test
    @WithMockUser
    @DisplayName("새 스크립트 화면은 돌아가는 예시와 계약을 함께 보여준다")
    void 새_스크립트_화면() throws Exception {
        mockMvc.perform(get("/connectors/{id}/scripts/new", connector.getId()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                // 빈 상자를 주면 아무도 시작하지 못한다
                .andExpect(content().string(containsString("json.load(sys.stdin)")))
                // 계약이 늘 보여야 외울 것이 없다
                .andExpect(content().string(containsString("_row")))
                .andExpect(content().string(containsString("안 담은 칸은")));
    }

    /** 저장하면 목록에 뜨고, 고치면 새 버전이 된다. */
    @Test
    @WithMockUser
    @DisplayName("저장한 스크립트가 칸 연결 화면 목록에 뜬다")
    void 저장하면_목록에_뜬다() throws Exception {
        mockMvc.perform(post("/connectors/{id}/scripts", connector.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("name", "이름 다듬기")
                        .param("body", "print('{\"rows\": []}')"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/connectors/{id}/mapping", connector.getId()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                .andExpect(content().string(containsString("이름 다듬기")))
                // 스크립트가 막혀도 순서를 바꿀 길이 남아야 한다
                .andExpect(content().string(containsString("data-move-fallback")));
    }

    /**
     * 고치면 새 버전이 되는가.
     *
     * <p>덮어쓰면 나중에 「이 이름이 왜 이렇게 됐지」 를 설명할 방법이 사라진다.
     */
    @Test
    @WithMockUser
    @DisplayName("고치면 새 버전이 되고 지난 것은 남는다")
    void 고치면_버전이_오른다() throws Exception {
        PostScript first = PostScript.draft(TENANT, connector.getId(), "이름 다듬기", "print(1)", 1, 1);
        first.activate();
        scriptRepository.save(first);

        mockMvc.perform(post("/connectors/{id}/scripts", connector.getId())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("scriptId", first.getId().toString())
                        .param("name", "이름 다듬기")
                        .param("body", "print(2)"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/connectors/{id}/mapping", connector.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("v2")));
    }

    /** 꺼 두면 건너뛴다는 사실이 화면에 보여야 「왜 안 돌지」가 안 생긴다. */
    @Test
    @WithMockUser
    @DisplayName("꺼 둔 스크립트는 건너뛴다고 화면이 말한다")
    void 꺼두면_말해준다() throws Exception {
        PostScript script = PostScript.draft(TENANT, connector.getId(), "쉬는 것", "print(1)", 1, 1);
        script.activate();
        script.changeEnabled(false);
        scriptRepository.save(script);

        mockMvc.perform(get("/connectors/{id}/mapping", connector.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("건너뜁니다")));
    }
}
