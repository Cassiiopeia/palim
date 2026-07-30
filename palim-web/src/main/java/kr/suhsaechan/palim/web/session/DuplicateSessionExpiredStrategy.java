package kr.suhsaechan.palim.web.session;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;

/**
 * 만료된 세션의 요청을 로그인 화면으로 보낸다.
 *
 * <p>기본 동작은 본문에 짧은 오류 문장을 그대로 출력하는 것이다. 그러면 발주자는 화면이 깨진
 * 것으로 받아들이고 무슨 일이 일어났는지 알 수 없다. 이유를 붙여 로그인 화면으로 보낸다.
 *
 * <p>감사 로그는 여기서 남기지 않는다. 강제 종료는 {@code DuplicateLoginController} 가 만료를
 * 지시한 시점에 이미 {@code LOGOUT_DUPLICATE} 로 기록했다. 여기서 또 남기면 <b>기존 세션이
 * 요청을 보낼 때마다</b> 같은 사건이 중복 기록된다.
 */
@Slf4j
public class DuplicateSessionExpiredStrategy implements SessionInformationExpiredStrategy {

    @Override
    public void onExpiredSessionDetected(SessionInformationExpiredEvent event) throws IOException {
        log.debug("만료된 세션의 요청 — {}", event.getSessionInformation().getSessionId());
        event.getResponse().sendRedirect(
                event.getRequest().getContextPath() + "/login?expired=duplicate");
    }
}
