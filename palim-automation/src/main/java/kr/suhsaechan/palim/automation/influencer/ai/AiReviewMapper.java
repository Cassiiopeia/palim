package kr.suhsaechan.palim.automation.influencer.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Basis;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Confidence;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Evidence;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.EvidenceSource;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Recommendation;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Risk;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.ScoredItem;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Severity;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Verdict;
import tools.jackson.databind.JsonNode;

/** AI JSON 응답 → 도메인 레코드. 스키마가 strict 라 방어 코드는 최소로 둔다. */
final class AiReviewMapper {

    private AiReviewMapper() {
    }

    static AiReviewResult from(JsonNode node) {
        return new AiReviewResult(
                risks(node.path("risks")),
                scored(node.path("brandSafety")),
                scored(node.path("campaignFit")),
                scored(node.path("audienceQuality")),
                verdict(node.path("verdict")),
                enumOf(Confidence.class, node.path("confidence").asString("medium")));
    }

    private static List<Risk> risks(JsonNode array) {
        List<Risk> risks = new ArrayList<>();
        for (JsonNode item : array) {
            risks.add(new Risk(
                    item.path("claim").asString(),
                    enumOf(Basis.class, item.path("basis").asString("inferred")),
                    evidence(item.path("evidence")),
                    enumOf(Severity.class, item.path("severity").asString("low"))));
        }
        return risks;
    }

    private static ScoredItem scored(JsonNode node) {
        List<String> reasons = new ArrayList<>();
        node.path("reasons").forEach(reason -> reasons.add(reason.asString()));
        return new ScoredItem(node.path("score").asDouble(0), reasons,
                evidence(node.path("evidence")), false);
    }

    private static List<Evidence> evidence(JsonNode array) {
        List<Evidence> evidence = new ArrayList<>();
        for (JsonNode item : array) {
            evidence.add(new Evidence(
                    enumOf(EvidenceSource.class, item.path("source").asString("comment")),
                    item.path("quote").asString()));
        }
        return evidence;
    }

    private static Verdict verdict(JsonNode node) {
        List<String> conditions = new ArrayList<>();
        node.path("conditions").forEach(condition -> conditions.add(condition.asString()));
        return new Verdict(node.path("headline").asString(),
                enumOf(Recommendation.class, node.path("recommend").asString("hold")),
                conditions);
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    }
}
