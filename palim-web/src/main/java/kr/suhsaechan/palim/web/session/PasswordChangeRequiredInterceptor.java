package kr.suhsaechan.palim.web.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.Set;
import kr.suhsaechan.palim.auth.AdminAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 초기 비밀번호를 쓰는 동안 다른 화면을 막는다 (#51).
 *
 * <p>기본 계정(`admin`)의 비밀번호는 비밀이 아니라 <b>공개된 값</b>이다. 이 저장소는 PUBLIC 이고
 * 화면은 인터넷에 노출되므로, 변경하지 않은 상태로 쓰이면 누구나 재고를 조작하고 API 키를
 * 갈아치울 수 있다.
 *
 * <p>계정을 잠그는 대신 <b>변경을 강제</b>하는 이유는, 잠가버리면 발주자도 못 들어와 시스템
 * 자체가 무용지물이 되기 때문이다.
 *
 * <h2>왜 필터가 아니라 인터셉터인가</h2>
 *
 * <p>인증이 끝난 뒤에만 판정하면 되고, 정적 자원·로그인 경로를 제외하는 규칙을 MVC 설정 한 곳에
 * 모을 수 있다. 보안 필터 체인에 넣으면 인증 흐름과 뒤엉켜 로그인 자체가 막히는 사고가 나기 쉽다.
 */
@Slf4j
@RequiredArgsConstructor
public class PasswordChangeRequiredInterceptor implements HandlerInterceptor {

    /**
     * 변경 강제 중에도 통과시키는 경로.
     *
     * <p>변경 화면과 로그아웃은 반드시 열어둬야 한다 — 둘 다 막으면 사용자가 아무것도 할 수 없는
     * 상태가 되고, 그것은 잠근 것과 같다.
     */
    private static final Set<String> ALLOWED = Set.of(
            "/settings/account",
            "/settings/account/password",
            "/logout");

    private final AdminAccountService adminAccountService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {

        Principal principal = request.getUserPrincipal();
        if (principal == null) {
            return true;
        }

        String path = request.getRequestURI();
        if (ALLOWED.contains(path)) {
            return true;
        }

        boolean required = adminAccountService.findByUsername(principal.getName())
                .map(account -> account.isPasswordChangeRequired())
                .orElse(false);

        if (!required) {
            return true;
        }

        log.debug("초기 비밀번호 사용 중 — 변경 화면으로 이동 ({})", path);
        response.sendRedirect(request.getContextPath() + "/settings/account");
        return false;
    }
}
