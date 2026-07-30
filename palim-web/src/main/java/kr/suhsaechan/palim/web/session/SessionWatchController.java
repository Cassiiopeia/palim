package kr.suhsaechan.palim.web.session;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 세션 강제 종료 통보 채널.
 *
 * <p>모든 화면이 이 엔드포인트에 연결해 두고, 같은 계정으로 다른 곳에서 로그인하면 서버가
 * 이벤트를 밀어준다. 브라우저는 안내를 띄운 뒤 로그인 화면으로 이동한다.
 *
 * <p>조회 감사 대상이 아니다 — {@code ScreenNames} 에 등록하지 않았다. 이 연결까지 감사 로그로
 * 남기면 재연결마다 행이 쌓여 목록이 잡음으로 덮인다.
 */
@RestController
@RequiredArgsConstructor
public class SessionWatchController {

    private final SessionWatchRegistry sessionWatchRegistry;

    @GetMapping(path = "/api/session/watch", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter watch(HttpServletRequest request) {
        return sessionWatchRegistry.subscribe(request.getSession().getId());
    }
}
