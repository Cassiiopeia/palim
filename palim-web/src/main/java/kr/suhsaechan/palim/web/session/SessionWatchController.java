package kr.suhsaechan.palim.web.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    public SseEmitter watch(HttpServletRequest request, HttpServletResponse response) {
        // 역방향 프록시(nginx 계열)는 응답을 버퍼에 모았다가 한 번에 내보낸다. 그러면 이벤트가
        // 발생 시점에 도달하지 않아 중복 로그인 통보가 늦거나 아예 오지 않는다. 시놀로지 DSM
        // 역방향 프록시에는 proxy_buffering 을 끄는 설정 화면이 없으므로 이 헤더로 끈다.
        response.setHeader("X-Accel-Buffering", "no");
        // 중간 캐시가 이벤트 스트림을 저장하지 않도록 한다.
        response.setHeader("Cache-Control", "no-cache, no-store");
        return sessionWatchRegistry.subscribe(request.getSession().getId());
    }
}
