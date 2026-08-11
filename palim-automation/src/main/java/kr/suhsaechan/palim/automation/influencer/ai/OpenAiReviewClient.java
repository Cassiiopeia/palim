package kr.suhsaechan.palim.automation.influencer.ai;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.common.config.ConfigReader;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 구조화 출력 호출.
 *
 * <p>공식 Java SDK 대신 {@link RestClient} 로 직접 호출한다. 05-INTEGRATION 이 요구하는 핵심은
 * <b>"py 경유 금지 · Java 에서 직접 · 항상 구조화 출력"</b> 이고 그 셋을 모두 지킨다. SDK 를 넣지
 * 않는 이유는 이 프로젝트가 쓰는 표면이 <b>채팅 완성 한 엔드포인트뿐</b>이라 얻는 것이 적고,
 * SDK 버전이 오르면 내부망 미러 사정에 빌드가 묶이기 때문이다. 텔레그램 클라이언트와도 같은
 * 방식이라 코드 결이 유지된다.
 *
 * <p>스키마는 {@code strict} 로 보낸다 — 필드 누락·타입 불일치를 모델 쪽에서 막아야 파싱 코드가
 * 방어 로직으로 뒤덮이지 않는다. 그래도 통과할 수 있는 문제(지어낸 인용·범위 밖 점수)는
 * {@link AiReviewValidator} 가 다시 거른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiReviewClient implements InfluencerAiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiProperties aiProperties;
    private final ConfigReader config;
    private final ReviewPromptBuilder promptBuilder;

    private volatile RestClient restClient;

    @Override
    public AiReviewResult review(ReviewInput input, AiScorePoints points) {
        if (!aiProperties.isConfigured()) {
            throw new BusinessException(ErrorCode.AI_NOT_CONFIGURED);
        }

        String promptVersion = config.getString(AiConfigKeys.PROMPT_VERSION);
        Map<String, Object> body = Map.of(
                "model", config.getString(AiConfigKeys.MODEL),
                // 온도 0 — 같은 채널을 다시 열었을 때 점수가 달라 보이면 그 순간 신뢰를 잃는다.
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt(promptVersion)),
                        Map.of("role", "user", "content", promptBuilder.buildUserMessage(input, points))),
                "response_format", Map.of(
                        "type", "json_schema",
                        "json_schema", Map.of(
                                "name", "influencer_review",
                                "strict", true,
                                "schema", AiReviewSchema.schema())));

        try {
            JsonNode response = restClient().post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + aiProperties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            String content = response.path("choices").path(0).path("message").path("content")
                    .asString();
            if (content.isBlank()) {
                throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "빈 응답");
            }
            return AiReviewMapper.from(MAPPER.readTree(content));

        } catch (RestClientException e) {
            log.error("AI 심사 호출 실패", e);
            throw new BusinessException(ErrorCode.AI_CALL_FAILED, e.getMessage());

        } catch (RuntimeException e) {
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            log.error("AI 응답 해석 실패", e);
            throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, e.getMessage());
        }
    }

    /** 프롬프트는 리소스 파일로 버전 관리한다(05-INTEGRATION). */
    private String systemPrompt(String version) {
        return read("prompts/influencer-review-%s.md".formatted(version))
                + "\n\n"
                + read("prompts/influencer-review-examples-%s.md".formatted(version));
    }

    private String read(String path) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            log.error("프롬프트 파일을 읽을 수 없습니다 — {}", path, e);
            throw new BusinessException(ErrorCode.AI_PROMPT_NOT_FOUND, path);
        }
    }

    /** 설정을 읽으므로 생성자가 아니라 첫 호출에서 만든다(ConfigReader 규칙). */
    private RestClient restClient() {
        RestClient local = restClient;
        if (local == null) {
            synchronized (this) {
                local = restClient;
                if (local == null) {
                    JdkClientHttpRequestFactory requestFactory =
                            new JdkClientHttpRequestFactory(HttpClient.newHttpClient());
                    requestFactory.setReadTimeout(
                            Duration.ofSeconds(aiProperties.timeoutSeconds()));
                    local = RestClient.builder()
                            .baseUrl(aiProperties.baseUrl())
                            .requestFactory(requestFactory)
                            .build();
                    restClient = local;
                }
            }
        }
        return local;
    }
}
