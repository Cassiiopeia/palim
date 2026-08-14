package kr.suhsaechan.palim.integration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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

    /**
     * 고른 시스템에 맞는 것을 묻는가.
     *
     * <p>물류 시스템은 <b>인증키가 없다.</b> 평소 로그인에 쓰는 아이디·비밀번호로 붙는다.
     * 그런데 화면은 시스템을 바꿔도 「API 인증키」·「테스트 인증키/정식 인증키」·「이카운트에서
     * IP 등록」을 그대로 보여줬다. 문구를 갈아 끼우는 코드가 화면 안에 박혀 있었고, 그것은
     * 보안 정책에 막혀 <b>한 줄도 실행되지 않았기</b> 때문이다.
     *
     * <p>화면은 200 으로 멀쩡히 열렸다. 그래서 기존 렌더 테스트는 전부 통과했다. 사장님이
     * 「아이디 비번을 입력해야 되는 거 아니야?」 라고 물어서야 드러났다.
     */
    @Test
    @WithMockUser
    @DisplayName("물류 시스템을 고르면 인증키가 아니라 비밀번호를 묻는다")
    void 물류시스템은_비밀번호를_묻는다() throws Exception {
        mockMvc.perform(get("/connectors/connect").param("preset", "ONEWMS"))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                .andExpect(content().string(containsString("비밀번호")))
                // 이 시스템에는 테스트 키라는 개념 자체가 없다. 물으면 있지도 않은 선택이 된다.
                .andExpect(content().string(not(containsString("지금 넣는 키"))))
                // IP 등록은 이카운트 사정이다. 남겨 두면 하지 않아도 될 일을 찾아 헤맨다.
                .andExpect(content().string(not(containsString("넣기 전에 두 가지를 확인하세요"))))
                // 인증키 발급 절차는 인증키를 쓰는 시스템에만 띄운다.
                .andExpect(content().string(not(containsString("API인증키발급"))));
    }

    /** 이카운트를 고르면 반대로 인증키와 키 단계를 물어야 한다. 한쪽만 맞으면 고친 것이 아니다. */
    @Test
    @WithMockUser
    @DisplayName("이카운트를 고르면 인증키와 키 종류를 묻는다")
    void 이카운트는_인증키를_묻는다() throws Exception {
        mockMvc.perform(get("/connectors/connect").param("preset", "ECOUNT"))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                .andExpect(RenderAssertions.noInlineCode())
                .andExpect(content().string(containsString("API 인증키")))
                .andExpect(content().string(containsString("지금 넣는 키")))
                .andExpect(content().string(containsString("API인증키발급")));
    }

    /**
     * 시스템을 고른 뒤 제출하면 그 시스템으로 가는가.
     *
     * <p>고르는 폼과 입력 폼이 나뉘어 있다(폼 안에 폼을 넣을 수 없다). 그래서 고른 값을 입력
     * 폼이 <b>함께 실어 보내야</b> 한다. 빠뜨리면 물류 계정을 넣고 실행했는데 이카운트로
     * 가서, 「계정이 틀렸나」 부터 의심하게 된다.
     */
    @Test
    @WithMockUser
    @DisplayName("고른 시스템이 입력 폼에 실려 나간다")
    void 고른_시스템이_폼에_실린다() throws Exception {
        mockMvc.perform(get("/connectors/connect").param("preset", "ONEWMS"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"preset\" value=\"ONEWMS\"")));
    }
}
