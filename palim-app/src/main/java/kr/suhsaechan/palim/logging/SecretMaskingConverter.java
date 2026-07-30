package kr.suhsaechan.palim.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Pattern;

/**
 * 로그 메시지의 비밀값을 마스킹한다 (09-SECURITY).
 *
 * <p>{@code ChannelCredentialService} 경계 규칙(CLAUDE.md 7)의 로그 계층 버전이다. 호출부가
 * 조심해도 예외 메시지·외부 라이브러리 로그에 비밀값이 섞여 나올 수 있으므로, 로그가 파일에
 * 닿기 직전에 한 번 더 지운다. <b>마지막 방어선이지 유일한 방어선이 아니다</b> — 비밀값을
 * 로그에 넘기지 않는 것이 먼저다.
 *
 * <h2>패턴을 좁게 잡는 이유</h2>
 *
 * <p>{@code key} 단독 같은 넓은 패턴은 {@code dedupeKey=LOW_STOCK:...} 처럼 비밀이 아닌 값까지
 * 지워 장애 조사를 방해한다. 마스킹된 로그는 복원할 수 없으므로, 확실한 비밀 이름과 형식만
 * 마스킹한다.
 */
public class SecretMaskingConverter extends MessageConverter {

    /**
     * {@code password=...} · {@code apiKey: ...} 형태. 이름이 확실히 비밀인 것만.
     *
     * <p>{@code Bearer} 접두사를 값의 일부로 함께 삼킨다 — 접두사에서 끊으면
     * {@code authorization: Bearer abc} 의 실제 토큰이 마스킹 밖에 남는다.
     */
    private static final Pattern KEY_VALUE = Pattern.compile(
            // \b 가 중요하다. 없으면 nextToken(쿠팡 페이징 커서) 의 "Token" 이 걸려
            // 수집 디버깅에 필요한 값까지 지워진다.
            "(?i)\\b(password|passwd|secret|secretKey|token|api[-_]?key|access[-_]?key"
                    + "|private[-_]?key|credential|authorization|chat[-_]?id)"
                    + "\\s*[=:]\\s*(?:Bearer\\s+)?[^\\s,;&\"']+");

    /** 텔레그램 봇 토큰 형식({@code 123456789:AAF...}). URL 에 포함돼도 잡는다. */
    private static final Pattern TELEGRAM_TOKEN = Pattern.compile(
            "\\b\\d{8,10}:[A-Za-z0-9_-]{30,}\\b");

    private static final String MASK = "$1=***";

    @Override
    public String convert(ILoggingEvent event) {
        return mask(super.convert(event));
    }

    static String mask(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String masked = KEY_VALUE.matcher(message).replaceAll(MASK);
        return TELEGRAM_TOKEN.matcher(masked).replaceAll("***:***");
    }
}
