package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.test.web.servlet.ResultMatcher;

/**
 * 화면이 <b>끝까지</b> 그려졌는가.
 *
 * <p>«200 이 떨어졌다» 는 화면이 멀쩡하다는 뜻이 아니다. Thymeleaf 는 위에서부터 흘려보내며
 * 그리므로, 중간에서 표현식이 터지면 <b>그때까지 나간 부분은 이미 브라우저에 도착한 뒤</b>다.
 * 서버는 응답을 되돌릴 수 없어 오류 화면조차 못 띄우고, 사용자에게는 <b>버튼과 사이드바가
 * 사라진 반쪽 화면</b>이 남는다. 상태코드는 200 이다.
 *
 * <p>실제로 그렇게 배포됐다. 렌더 테스트는 있었지만 «열린다» 까지만 봤고, 잘린 뒤쪽을 아무도
 * 확인하지 않았다. 그래서 이 검사를 따로 둔다 — 화면을 검사하는 모든 테스트가 이것을 쓴다.
 */
final class RenderAssertions {

    private RenderAssertions() {
    }

    /**
     * 문서가 끝까지 왔는지 확인한다.
     *
     * <p>두 가지를 본다. 문서가 닫혔는지, 그리고 <b>레이아웃의 사이드바가 살아 있는지</b>다.
     * 사이드바는 본문 <b>뒤에</b> 오므로, 본문이 중간에 터지면 가장 먼저 사라지는 것이 이것이다.
     * 사용자가 «메뉴가 없어졌다» 로 알아채는 지점이기도 하다.
     */
    static ResultMatcher fullyRendered() {
        return result -> {
            String body = result.getResponse().getContentAsString();
            assertThat(body.trim())
                    .as("문서가 닫히지 않았다 — 렌더 도중 끊긴 화면이다")
                    .endsWith("</html>");
            assertThat(body)
                    .as("사이드바가 없다 — 본문이 중간에 터져 레이아웃 뒷부분이 잘렸다")
                    .contains("로그아웃");
        };
    }
}
