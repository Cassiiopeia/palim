package kr.suhsaechan.palim.notification.telegram;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 텔레그램 Bot API 설정.
 *
 * <p>봇 토큰이 없어도 <b>기동을 막지 않는다.</b> 암호화 마스터키와 달리 텔레그램은 발주자가
 * 나중에 설정하는 항목이고, 연결되지 않은 동안 알림은 Outbox 에 쌓여 유실되지 않기 때문이다.
 *
 * @param botToken   BotFather 가 발급한 토큰
 * @param apiBaseUrl API 기본 주소
 * @param timeout    호출 타임아웃
 * @param maxAttempts 발송 재시도 한도. 초과하면 Outbox 를 FAILED 로 두고 사람이 확인한다
 */
@ConfigurationProperties(prefix = "palim.telegram")
public record TelegramProperties(
        String botToken,
        String apiBaseUrl,
        Duration timeout,
        int maxAttempts
) {

    private static final String DEFAULT_API_BASE_URL = "https://api.telegram.org";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_ATTEMPTS = 5;

    public TelegramProperties {
        apiBaseUrl = apiBaseUrl != null && !apiBaseUrl.isBlank() ? apiBaseUrl : DEFAULT_API_BASE_URL;
        timeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        maxAttempts = maxAttempts > 0 ? maxAttempts : DEFAULT_MAX_ATTEMPTS;
    }

    public boolean isConfigured() {
        return botToken != null && !botToken.isBlank();
    }
}
