package kr.suhsaechan.palim.web.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import kr.suhsaechan.palim.audit.AuditType;
import kr.suhsaechan.palim.web.audit.WebAuditRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * 로그인 실패를 기록하고 실패 횟수를 누적한다.
 *
 * <h2>사용자에게는 실패 이유를 구분해 알려주지 않는다</h2>
 *
 * <p>"없는 아이디" · "비밀번호 틀림" · "잠긴 계정" 을 구분해 보여주면 공격자가 <b>유효한 아이디
 * 목록을 응답 차이로 수집</b>할 수 있다. 화면에는 항상 같은 메시지를 띄우고, 구분은 감사 로그에만
 * 남긴다 — 감사 로그는 로그인한 관리자만 본다.
 *
 * <p>잠긴 계정의 실패는 카운트를 올리지 않는다. 올리면 잠금 시간이 시도마다 연장돼 정상 사용자가
 * 영구히 못 들어오는 상태가 만들어진다.
 */
@Slf4j
@RequiredArgsConstructor
public class LoginFailureHandler implements AuthenticationFailureHandler {

    private static final String FAILURE_URL = "/login?error";

    private final LoginAttemptService loginAttemptService;
    private final WebAuditRecorder webAuditRecorder;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {

        String username = request.getParameter("username");

        if (exception instanceof LockedException) {
            webAuditRecorder.recordAuth(AuditType.LOGIN_BLOCKED_LOCKED, username, request);
            log.warn("잠긴 계정 로그인 시도 — {}", username);
        } else {
            webAuditRecorder.recordAuth(AuditType.LOGIN_FAILURE, username, request);

            if (loginAttemptService.recordFailure(username)) {
                webAuditRecorder.recordAuth(AuditType.ACCOUNT_LOCKED, username, request);
                log.warn("로그인 실패 누적으로 계정 잠금 — {}", username);
            }
        }

        response.sendRedirect(request.getContextPath() + FAILURE_URL);
    }
}
