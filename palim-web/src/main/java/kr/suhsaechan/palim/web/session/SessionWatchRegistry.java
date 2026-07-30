package kr.suhsaechan.palim.web.session;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 세션이 강제 종료됐음을 브라우저에 즉시 알린다.
 *
 * <h2>폴링하지 않는다</h2>
 *
 * <p>사내 CM 은 모든 화면에서 {@code setInterval} 로 100ms 마다 중복 여부를 물어본다. 관리자
 * 1명이 쓰는 화면에서 초당 10회 요청은 순수한 낭비고, 그 요청 하나하나가 세션 최근 사용 시각을
 * 갱신해 <b>유휴 타임아웃이 영원히 발동하지 않는 부작용</b>까지 만든다.
 *
 * <p>서버가 아는 사실(다른 곳에서 로그인했다)을 서버가 알려주면 된다. SSE 로 밀어준다.
 *
 * <h2>SSE 가 없어도 기능은 성립한다</h2>
 *
 * <p>연결이 끊기거나 브라우저가 지원하지 않아도, 기존 세션은 다음 요청에서
 * {@code ConcurrentSessionFilter} 가 만료 처리한다. SSE 는 <b>즉시성만</b> 담당한다.
 */
@Slf4j
@Component
public class SessionWatchRegistry {

    /** 이벤트 이름. 브라우저 쪽 {@code addEventListener} 와 짝을 맞춘다. */
    private static final String EVENT_DUPLICATE = "duplicate";

    /**
     * 연결 유지 시간.
     *
     * <p>세션 유휴 타임아웃보다 짧게 둔다. 길게 잡으면 세션이 이미 죽은 뒤에도 연결이 남아
     * 서버 스레드를 붙잡는다. 만료되면 브라우저가 자동으로 재연결한다.
     */
    private static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(10);

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String sessionId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());

        emitter.onCompletion(() -> emitters.remove(sessionId, emitter));
        emitter.onTimeout(() -> emitters.remove(sessionId, emitter));
        emitter.onError(throwable -> emitters.remove(sessionId, emitter));

        // 같은 세션이 재연결하면 이전 연결을 정리한다. 남겨두면 죽은 연결에 계속 쓰게 된다.
        SseEmitter previous = emitters.put(sessionId, emitter);
        if (previous != null) {
            previous.complete();
        }
        return emitter;
    }

    /**
     * 해당 세션에 강제 종료를 통보한다.
     *
     * <p>연결이 없으면 조용히 넘어간다. 통보 실패는 오류가 아니다 — 만료 자체는 이미 적용됐고,
     * 브라우저는 다음 요청에서 알게 된다.
     */
    public void notifyDuplicateLogin(String sessionId) {
        SseEmitter emitter = emitters.remove(sessionId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(EVENT_DUPLICATE).data("1"));
            emitter.complete();
        } catch (IOException | IllegalStateException exception) {
            log.debug("세션 종료 통보 실패 — 연결이 이미 닫혔다: {}", sessionId);
            emitter.completeWithError(exception);
        }
    }
}
