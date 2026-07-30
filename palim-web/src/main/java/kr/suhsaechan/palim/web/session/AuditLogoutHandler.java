package kr.suhsaechan.palim.web.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.suhsaechan.palim.audit.AuditType;
import kr.suhsaechan.palim.web.audit.WebAuditRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;

/**
 * 로그아웃을 감사 로그로 남긴다.
 *
 * <p>{@code LogoutSuccessHandler} 가 아니라 {@code LogoutHandler} 다. 성공 핸들러는 세션 무효화
 * <b>이후</b>에 호출되므로 그 시점에는 누가 로그아웃했는지 알 수 없다.
 */
@RequiredArgsConstructor
public class AuditLogoutHandler implements LogoutHandler {

    private final WebAuditRecorder webAuditRecorder;

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response,
                       Authentication authentication) {
        if (authentication == null) {
            // 로그인하지 않은 상태의 로그아웃 요청. 기록할 actor 가 없다.
            return;
        }
        webAuditRecorder.recordAuth(AuditType.LOGOUT, authentication.getName(), request);
    }
}
