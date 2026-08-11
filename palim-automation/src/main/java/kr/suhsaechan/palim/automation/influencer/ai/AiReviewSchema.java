package kr.suhsaechan.palim.automation.influencer.ai;

import java.util.List;
import java.util.Map;

/**
 * 구조화 출력 스키마.
 *
 * <p>{@code risks} 가 <b>첫 속성</b>인 것이 이 스키마의 핵심 설계다. 구조화 출력은 속성 순서대로
 * 생성되므로 반대 논거가 점수보다 먼저 쓰이고, 그 내용이 뒤따르는 점수에 반영된다. 점수를 먼저
 * 매기게 하면 AI 는 그 점수를 정당화하는 방향으로 근거를 고른다.
 *
 * <p>{@code evidence} 의 {@code minItems: 1} 도 의도다 — 근거 없는 주장 자체를 스키마에서 막는다.
 */
public final class AiReviewSchema {

    private AiReviewSchema() {
    }

    public static Map<String, Object> schema() {
        return object(
                Map.of(
                        "risks", array(riskSchema(), 5),
                        "brandSafety", scoredSchema(),
                        "campaignFit", scoredSchema(),
                        "audienceQuality", scoredSchema(),
                        "verdict", verdictSchema(),
                        "confidence", enumString("low", "medium", "high")),
                List.of("risks", "brandSafety", "campaignFit", "audienceQuality", "verdict",
                        "confidence"));
    }

    private static Map<String, Object> riskSchema() {
        return object(
                Map.of(
                        "claim", string(200),
                        "basis", enumString("observed", "inferred"),
                        "evidence", array(evidenceSchema(), 3),
                        "severity", enumString("low", "medium", "high")),
                List.of("claim", "basis", "evidence", "severity"));
    }

    private static Map<String, Object> scoredSchema() {
        return object(
                Map.of(
                        "score", Map.of("type", "number"),
                        "reasons", array(string(200), 3),
                        "evidence", array(evidenceSchema(), 3)),
                List.of("score", "reasons", "evidence"));
    }

    private static Map<String, Object> evidenceSchema() {
        return object(
                Map.of(
                        "source", enumString("transcript", "comment", "metadata"),
                        // 인용은 원문 그대로여야 검증을 통과한다. 프롬프트가 이를 명시한다.
                        "quote", string(300)),
                List.of("source", "quote"));
    }

    private static Map<String, Object> verdictSchema() {
        return object(
                Map.of(
                        "headline", string(300),
                        "recommend", enumString("propose", "conditional", "hold", "avoid"),
                        "conditions", array(string(200), 3)),
                List.of("headline", "recommend", "conditions"));
    }

    private static Map<String, Object> object(Map<String, Object> properties,
                                              List<String> required) {
        // strict 모드는 additionalProperties=false 와 required 전체 지정을 요구한다.
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required,
                "additionalProperties", false);
    }

    private static Map<String, Object> array(Map<String, Object> items, int maxItems) {
        return Map.of("type", "array", "items", items, "maxItems", maxItems);
    }

    private static Map<String, Object> string(int maxLength) {
        return Map.of("type", "string", "maxLength", maxLength);
    }

    private static Map<String, Object> enumString(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }
}
