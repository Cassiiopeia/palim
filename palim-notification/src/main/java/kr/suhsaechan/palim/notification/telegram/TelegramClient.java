package kr.suhsaechan.palim.notification.telegram;

import java.net.http.HttpClient;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 텔레그램 Bot API 호출.
 *
 * <p>예외를 던지지 않고 {@link TelegramSendResult} 를 반환한다. 발송 실패는 정상 흐름의
 * 일부이며, 호출자가 <b>재시도 가능 여부를 반드시 확인</b>하게 만들어야 하기 때문이다.
 *
 * <p>{@code RestClient.Builder} 빈을 주입받지 않는다. 이 모듈은 web starter 를 의존하지 않으므로
 * 그 빈이 없을 수 있고, 있더라도 화면 계층의 설정이 알림 발송에 영향을 주는 것은 바람직하지 않다.
 */
@Slf4j
@Component
public class TelegramClient {

    private final RestClient restClient;
    private final TelegramProperties telegramProperties;

    public TelegramClient(TelegramProperties telegramProperties) {
        this.telegramProperties = telegramProperties;

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newHttpClient());
        requestFactory.setReadTimeout(telegramProperties.timeout());

        this.restClient = RestClient.builder()
                .baseUrl(telegramProperties.apiBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 메시지를 발송한다.
     *
     * <p>4xx 는 재시도해도 성공하지 않는다 — 잘못된 chat_id, 봇 차단, 토큰 무효 같은 경우이며
     * 계속 시도하면 호출 제한만 소모한다. 5xx·타임아웃은 재시도 대상이다.
     */
    public TelegramSendResult sendMessage(String chatId, String text) {
        if (!telegramProperties.isConfigured()) {
            return TelegramSendResult.permanentFailure("텔레그램 봇 토큰이 설정되지 않았습니다");
        }
        if (chatId == null || chatId.isBlank()) {
            return TelegramSendResult.permanentFailure("텔레그램 chat_id 가 설정되지 않았습니다");
        }

        try {
            restClient.post()
                    .uri("/bot{token}/sendMessage", telegramProperties.botToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "chat_id", chatId,
                            "text", text,
                            "disable_web_page_preview", true))
                    .retrieve()
                    .toBodilessEntity();
            return TelegramSendResult.sent();

        } catch (org.springframework.web.client.HttpClientErrorException exception) {
            String reason = "%s %s".formatted(exception.getStatusCode(), exception.getResponseBodyAsString());
            log.error("텔레그램 발송 거부 (재시도 불가) — {}", reason);
            return TelegramSendResult.permanentFailure(reason);

        } catch (org.springframework.web.client.HttpServerErrorException exception) {
            String reason = "%s".formatted(exception.getStatusCode());
            log.warn("텔레그램 서버 오류 (재시도 예정) — {}", reason);
            return TelegramSendResult.transientFailure(reason);

        } catch (RuntimeException exception) {
            // 타임아웃·연결 실패. 네트워크는 회복되므로 재시도한다.
            String reason = exception.getMessage() != null
                    ? exception.getMessage()
                    : exception.getClass().getSimpleName();
            log.warn("텔레그램 발송 실패 (재시도 예정) — {}", reason);
            return TelegramSendResult.transientFailure(reason);
        }
    }
}
