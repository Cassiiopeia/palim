package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** {@code src} 없는 {@code <script>} — 화면 안에 박힌 코드다. */
    private static final Pattern INLINE_SCRIPT =
            Pattern.compile("<script(?![^>]*\\ssrc\\s*=)[^>]*>", Pattern.CASE_INSENSITIVE);

    /** {@code style="…"} 속성. */
    private static final Pattern INLINE_STYLE =
            Pattern.compile("\\sstyle\\s*=\\s*[\"']", Pattern.CASE_INSENSITIVE);

    /** {@code onchange="…"} 처럼 태그에 직접 붙인 동작. */
    private static final Pattern INLINE_HANDLER = Pattern.compile(
            "\\son(click|change|submit|input|load|error|focus|blur|key\\w+|mouse\\w+)\\s*=",
            Pattern.CASE_INSENSITIVE);

    /**
     * 화면에 <b>박힌 코드·스타일이 없는가</b>.
     *
     * <p>이 서비스는 스크립트와 스타일을 자기 도메인 파일로만 제한한다(07-DECISIONS 009).
     * 그래서 태그 안에 직접 적은 코드는 <b>브라우저가 실행하지 않는다.</b>
     *
     * <p>문제는 <b>아무 티가 안 난다</b>는 것이다. 화면은 200 으로 멀쩡히 열리고, 버튼과
     * 드롭다운도 그대로 보인다. 다만 눌러도 아무 일이 일어나지 않는다. 실제로 연결 화면은
     * 시스템을 바꿔도 문구가 하나도 안 바뀌었고 — 물류 시스템을 골라도 「API 인증키」를
     * 넣으라고 했다 — 인플루언서 화면의 필터도 고르기만 하고 조회되지 않았다. 콘솔에만
     * 기록이 남아 사람 눈으로는 찾기 어렵다.
     *
     * <p>정책은 바꾸지 않는다. 대신 <b>정책을 어긴 화면이 테스트에서 걸리게</b> 한다.
     */
    static ResultMatcher noInlineCode() {
        return result -> {
            String body = result.getResponse().getContentAsString();
            assertNotFound(INLINE_SCRIPT, body,
                    "화면에 박힌 <script> 가 있다 — 브라우저가 실행하지 않으므로 그 기능은 죽어 있다."
                            + " /js 아래 파일로 옮겨라");
            assertNotFound(INLINE_STYLE, body,
                    "style 속성이 있다 — 정책에 막혀 적용되지 않는다. 클래스로 바꿔라");
            assertNotFound(INLINE_HANDLER, body,
                    "태그에 직접 붙인 동작(onchange 등)이 있다 — 실행되지 않는다."
                            + " data-auto-submit 처럼 표시만 하고 동작은 파일에 둬라");
        };
    }

    private static void assertNotFound(Pattern pattern, String body, String reason) {
        Matcher matcher = pattern.matcher(body);
        if (matcher.find()) {
            // 어디인지 보여줘야 고칠 수 있다. 「어딘가에 있다」 만으로는 파일을 다 뒤져야 한다.
            int from = Math.max(0, matcher.start() - 120);
            int to = Math.min(body.length(), matcher.end() + 120);
            assertThat(matcher.group())
                    .as("%s%n주변:%n…%s…", reason, body.substring(from, to))
                    .isNull();
        }
    }
}
