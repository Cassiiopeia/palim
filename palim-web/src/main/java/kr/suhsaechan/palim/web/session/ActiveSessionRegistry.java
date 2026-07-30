package kr.suhsaechan.palim.web.session;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 접속 중인 세션의 부가 정보를 들고 있다.
 *
 * <h2>Spring 의 {@code SessionRegistry} 와 역할이 다르다</h2>
 *
 * <p>세션 만료 처리({@code expireNow()} → {@code ConcurrentSessionFilter})는 Spring 의
 * {@code SessionRegistry} 가 담당한다. 이 클래스는 그것이 갖지 않는 <b>IP 와 로그인 시각</b>만
 * 보관한다. 두 개를 두는 게 중복처럼 보이지만, 만료 메커니즘을 직접 구현하면 다른 스레드의
 * 세션을 안전하게 끊는 문제를 다시 풀어야 한다.
 *
 * <h2>인메모리다</h2>
 *
 * <p>재기동하면 사라진다. 세션 자체도 인메모리라 함께 사라지므로 일관된다. 다중 인스턴스로
 * 확장하면 이 구조는 성립하지 않는다 — Palim 은 단일 인스턴스 운영을 전제한다(06-OPERATIONS).
 */
@Slf4j
@Component
public class ActiveSessionRegistry implements HttpSessionListener {

    private final Map<String, ActiveSession> sessions = new ConcurrentHashMap<>();

    public void register(String sessionId, String username, String clientIp) {
        sessions.put(sessionId, new ActiveSession(sessionId, username, clientIp, Instant.now()));
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    public Optional<ActiveSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** 같은 계정의 다른 접속. 자기 세션은 제외한다. */
    public List<ActiveSession> findOthers(String username, String currentSessionId) {
        return sessions.values().stream()
                .filter(session -> session.username().equals(username))
                .filter(session -> !session.sessionId().equals(currentSessionId))
                .toList();
    }

    /**
     * 세션이 사라지면 항목도 지운다.
     *
     * <p>이 리스너가 없으면 만료된 세션이 계속 "접속 중" 으로 남아, <b>발주자가 로그아웃한 뒤에도
     * 중복 로그인 확인창을 보게 된다.</b>
     */
    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        ActiveSession removed = sessions.remove(event.getSession().getId());
        if (removed != null) {
            log.debug("접속 종료 — {} {}", removed.username(), removed.clientIp());
        }
    }
}
