package kr.suhsaechan.palim.integration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 연결 화면이 <b>실제로 그려지는가</b>.
 *
 * <p>이 화면은 바깥 시스템을 붙이러 들어오는 <b>첫 화면</b>이다. 여기가 열리지 않으면 그 뒤로
 * 아무것도 할 수 없다.
 *
 * <p>실제로 한 번 죽었다. 실패한 경로에서만 담기는 값을 화면이 조건에 그대로 썼는데, 화면을
 * 처음 열 때는 그 값이 없어 <b>여는 순간</b> 터졌다. 표현식 오류는 컴파일에 걸리지 않으므로
 * 빌드도 테스트도 통과했고, 배포한 뒤에야 드러났다. 그래서 «열린다» 를 테스트로 못박는다.
 */
@AutoConfigureMockMvc
class ConnectScreenRenderIntegrationTest extends IntegrationTest {

    @Autowired private MockMvc mockMvc;

    /**
     * 아직 아무것도 실행하지 않은 상태 — 실행 결과에 딸린 값들이 전부 비어 있는 경로다.
     * 실제로 터졌던 것이 바로 이 경로다.
     */
    @Test
    @WithMockUser
    @DisplayName("처음 열 때 끝까지 그려진다")
    void 처음_열_때_그려진다() throws Exception {
        mockMvc.perform(get("/connectors/connect"))
                .andExpect(status().isOk())
                // 실행 버튼이 없으면 이 화면은 «있으나 마나» 다. 실제로 여기가 잘렸었다.
                .andExpect(content().string(containsString("연결 확인하기")))
                .andExpect(RenderAssertions.fullyRendered());
    }

    /**
     * 인증키 <b>만</b> 비어 막힌 경로. 여기서만 담기는 값이 있어 화면 조건이 갈린다 — 처음 열
     * 때와 이때가 <b>둘 다</b> 열려야 조건이 옳다.
     *
     * <p>다른 칸은 채운다. 회사코드가 먼저 비면 그 칸이 «빠진 칸» 으로 잡혀 인증키 안내까지
     * 가지 않는다.
     */
    @Test
    @WithMockUser
    @DisplayName("인증키가 비어 실패해도 화면이 그려진다")
    void 인증키가_비어도_그려진다() throws Exception {
        mockMvc.perform(post("/connectors/connect/test")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("preset", "ECOUNT")
                        .param("companyCode", "000000")
                        .param("userId", "tester")
                        .param("secret", ""))
                .andExpect(status().isOk())
                // 비밀값은 화면에 되돌리지 않으므로 «왜 또 비어 있지» 를 설명해야 한다.
                // 이 설명은 «실패했을 때만» 뜨는 알림이 아니라 인증키 칸에 항상 붙어 있는 한 줄이다
                // — 같은 사실을 상황에 따라 두 번 말하면 두 번 다 새 정보처럼 읽힌다.
                .andExpect(content().string(containsString("다시 실행할 때마다 붙여 넣어야 합니다")))
                // 실패한 뒤에도 다시 실행할 수 있어야 한다 — 버튼이 잘리면 여기서 막힌다
                .andExpect(content().string(containsString("연결 확인하기")))
                .andExpect(RenderAssertions.fullyRendered());
    }
}
