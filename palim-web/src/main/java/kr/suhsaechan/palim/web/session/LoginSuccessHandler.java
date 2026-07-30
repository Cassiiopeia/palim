package kr.suhsaechan.palim.web.session;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import kr.suhsaechan.palim.audit.AuditType;
import kr.suhsaechan.palim.web.audit.ClientIpResolver;
import kr.suhsaechan.palim.web.audit.WebAuditRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * 로그인 성공 처리와 중복 로그인 판정.
 *
 * <h2>같은 계정은 1세션, 다른 계정은 동시 접속 허용</h2>
 *
 * <p>Spring Security 의 {@code maximumSessions(1)} 로도 같은 결과를 얻을 수 있지만, 그 방식은
 * <b>기존 접속자에게 아무 것도 묻지 않고 세션을 끊는다.</b> 그러면 발주자는 자기 계정이 다른
 * 곳에서 쓰이고 있다는 사실을 알아챌 기회가 없다. 계정이 유출됐을 때 가장 먼저 드러나는 신호가
 * 바로 이 상황이다.
 *
 * <p>그래서 동시 세션 제한을 Spring 에 맡기지 않고({@code maximumSessions(-1)}) 여기서
 * 판정한다. 기존 접속이 있으면 로그인을 <b>보류</b>하고 확인 화면으로 보낸다.
 *
 * <h2>보류 상태에서는 인증되지 않은 상태로 되돌린다</h2>
 *
 * <p>비밀번호 검증은 끝났지만 아직 로그인이 아니다. 세션을 무효화하고 새 세션에 보류 정보만
 * 남긴다. "인증됐지만 특정 경로만 허용" 같은 중간 상태를 만들면 그 경로 필터에 구멍이 하나
 * 생기는 순간 인증 우회가 된다.
 */
@Slf4j
@RequiredArgsConstructor
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final String SUCCESS_URL = "/";
    private static final String DUPLICATE_URL = "/login/duplicate";

    private final ActiveSessionRegistry activeSessionRegistry;
    private final LoginAttemptService loginAttemptService;
    private final WebAuditRecorder webAuditRecorder;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication)
            throws IOException, ServletException {

        String username = authentication.getName();
        String clientIp = ClientIpResolver.resolve(request);
        HttpSession session = request.getSession();

        List<ActiveSession> others = activeSessionRegistry.findOthers(username, session.getId());

        if (others.isEmpty()) {
            completeLogin(request, session, username, clientIp);
            response.sendRedirect(request.getContextPath() + SUCCESS_URL);
            return;
        }

        holdLogin(request, response, session, username, clientIp, others);
    }

    private void completeLogin(HttpServletRequest request, HttpSession session,
                               String username, String clientIp) {
        loginAttemptService.recordSuccess(username, clientIp);
        activeSessionRegistry.register(session.getId(), username, clientIp);
        webAuditRecorder.recordAuth(AuditType.LOGIN_SUCCESS, username, request);
        log.info("로그인 — {} {}", username, clientIp);
    }

    /**
     * 로그인을 보류하고 확인 화면으로 보낸다.
     *
     * <p>세션을 무효화한 뒤 새로 만든다. 새 세션에는 보류 정보만 들어가므로 이 시점의 사용자는
     * 인증되지 않은 상태다.
     */
    private void holdLogin(HttpServletRequest request, HttpServletResponse response,
                           HttpSession session, String username, String clientIp,
                           List<ActiveSession> others) throws IOException {

        ActiveSession existing = others.getFirst();

        webAuditRecorder.recordAuth(AuditType.LOGIN_BLOCKED_DUPLICATE, username, request,
                "로그인 실패했습니다.(로그인 중복 — 기존 접속 %s)".formatted(existing.clientIp()));
        log.warn("중복 로그인 시도 — {} 신규 {} / 기존 {}", username, clientIp, existing.clientIp());

        PendingLogin pending = new PendingLogin(
                username, existing.clientIp(), others.size(), Instant.now());

        // 인증 상태를 완전히 걷어낸다. 세션 무효화만으로는 현재 요청의 스레드 로컬이 남는다.
        SecurityContextHolder.clearContext();
        session.invalidate();

        request.getSession(true).setAttribute(PendingLogin.SESSION_KEY, pending);
        response.sendRedirect(request.getContextPath() + DUPLICATE_URL);
    }
}
