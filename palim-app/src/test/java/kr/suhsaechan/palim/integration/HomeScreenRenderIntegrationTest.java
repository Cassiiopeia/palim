package kr.suhsaechan.palim.integration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 홈이 <b>지금 할 일</b>을 말하는가.
 *
 * <p>「시작하기」를 메뉴의 한 항목으로 두면 여러 기능 중 하나로 보인다. 순서를 안내하는 화면이
 * 순서 밖에 있으면 안 되므로 홈이 그 일을 한다.
 */
@AutoConfigureMockMvc
class HomeScreenRenderIntegrationTest extends IntegrationTest {

    @Autowired private MockMvc mockMvc;

    /**
     * 아직 아무것도 붙이지 않은 상태 — 처음 오는 사람이 보는 화면이다. 여기서 다음 한 걸음이
     * 안 보이면 사장님은 사이드바를 뒤져야 한다.
     */
    @Test
    @WithMockUser
    @DisplayName("준비가 안 끝났으면 다음 한 걸음을 짚어 준다")
    void 다음_걸음을_짚는다() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("재고 가져오는 곳")))
                .andExpect(content().string(containsString("하러 가기")))
                .andExpect(RenderAssertions.fullyRendered());
    }

    /** 옛 주소를 눌러도 같은 곳에 닿아야 한다. 북마크가 죽으면 「없어졌나」로 읽힌다. */
    @Test
    @WithMockUser
    @DisplayName("옛 시작하기 주소는 홈으로 보낸다")
    void 옛_주소는_홈으로() throws Exception {
        mockMvc.perform(get("/setup"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/"));
    }
}
