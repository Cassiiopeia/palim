package kr.suhsaechan.palim.automation.influencer.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI 접속 정보.
 *
 * <p>여기 있는 것은 비밀값과 엔드포인트뿐이다. 모델명·배점처럼 운영 중 조정하는 값은
 * {@code SystemConfig} 로 가야 화면에서 바꿀 수 있다.
 *
 * <p>API 키는 <b>사용량 하드 리밋이 걸린 키만</b> 쓴다(05-INTEGRATION). YouTube 와 달리 이쪽은
 * 실제로 과금되며, 프롬프트 버그 하나로 호출이 폭주하면 그대로 청구서가 된다.
 */
@ConfigurationProperties(prefix = "palim.ai")
public record AiProperties(String apiKey, String baseUrl, int timeoutSeconds) {

    public AiProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 120;
        }
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
