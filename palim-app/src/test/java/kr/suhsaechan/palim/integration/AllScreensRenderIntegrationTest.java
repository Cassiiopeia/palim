package kr.suhsaechan.palim.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * <b>모든 화면이 열리는가.</b>
 *
 * <p>화면 하나하나에 내용까지 확인하는 테스트를 두는 것이 가장 좋지만, 그것은 화면 수만큼
 * 시간이 든다. 그 사이에도 화면은 계속 늘어난다. 그래서 <b>모든 화면에 최소 한 겹</b>을 먼저
 * 깐다 — 「열린다, 그리고 끝까지 그려진다」.
 *
 * <p>이 한 겹이 잡는 것은 «상태코드는 200 인데 화면은 반쪽» 인 경우다. Thymeleaf 는 위에서부터
 * 흘려보내며 그리므로 중간에 터지면 그때까지 나간 부분이 이미 브라우저에 도착해 있다. 서버는
 * 오류 화면조차 띄우지 못하고, 사용자에게는 <b>버튼과 사이드바가 사라진 화면</b>이 남는다.
 * 실제로 그렇게 배포됐고, 테스트가 아니라 사람 눈이 먼저 찾았다.
 *
 * <p><b>비어 있는 상태로 연다.</b> 데이터를 하나도 넣지 않는다 — 처음 들어오는 사람이 보는
 * 화면이 정확히 이 상태이고, 목록이 비었을 때 터지는 표현식이 가장 흔하기 때문이다.
 */
@AutoConfigureMockMvc
class AllScreensRenderIntegrationTest extends IntegrationTest {

    @Autowired private MockMvc mockMvc;

    /**
     * 주소를 받는 화면은 여기 넣지 않는다. 그 화면들은 실제 자료가 있어야 의미가 있어
     * 각 도메인 테스트가 맡는다. 로그인 화면도 뺀다 — 사이드바가 없는 다른 레이아웃이다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/",
            "/connectors",
            "/connectors/new",
            "/connectors/units",
            "/connectors/models",
            "/mappings",
            "/skus",
            "/audit",
            "/monitor/collect",
            "/monitor/incidents",
            "/monitor/notifications",
            "/settings/account",
            "/settings/channels",
            "/settings/notification",
            "/settings/system",
            "/influencer/grades",
            "/influencer/rising",
            "/influencer/trends",
            "/influencer/campaigns",
            "/reconcile",
            "/reconcile/units",
    })
    @WithMockUser
    @DisplayName("화면이 비어 있는 상태에서도 끝까지 그려진다")
    void 화면이_끝까지_그려진다(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                // 「열린다」 와 「동작한다」 는 다르다. 태그에 박은 코드는 정책에 막혀 실행되지
                // 않는데도 화면은 멀쩡히 열려, 눌러도 아무 일이 없는 상태를 사람 눈으로만
                // 찾아야 했다. 실제로 두 화면이 그 상태로 배포됐다.
                .andExpect(RenderAssertions.noInlineCode());
    }

    /**
     * 설정 변경 이력은 <b>어느 설정의</b> 이력인지를 받아야 열린다. 위 목록에 넣으면 그 값 없이
     * 부르게 되어 화면이 아니라 요청 자체를 검사하게 된다.
     *
     * <p>바꾼 적 없는 설정을 골라 «이력이 하나도 없는» 상태로 연다 — 목록이 빌 때 터지는 것이
     * 가장 흔하기 때문이다.
     */
    @Test
    @WithMockUser
    @DisplayName("변경 이력이 하나도 없어도 이력 화면이 그려진다")
    void 이력_화면이_그려진다() throws Exception {
        mockMvc.perform(get("/settings/system/history").param("configKey", "존재하지-않는-설정"))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered());
    }
}
