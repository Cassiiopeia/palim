package kr.suhsaechan.palim.automation.influencer.ai;

import java.util.List;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Confidence;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Evidence;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Recommendation;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Risk;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.ScoredItem;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Severity;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Verdict;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 출력 검증 — 스키마를 지켜도 무의미한 결과가 나올 수 있어 코드가 다시 거른다.
 *
 * <p>규칙은 넷이다:
 * <ol>
 *   <li><b>인용 검증</b> — 원문에 없는 근거는 버린다. 근거가 모두 사라진 항목은 점수를
 *       중립값(만점의 60%)으로 대체한다. 0점으로 만들면 AI 실수가 채널을 부당하게 탈락시키고,
 *       만점으로 두면 검증이 무의미해진다</li>
 *   <li><b>점수 범위</b> — 배점을 넘는 값은 잘라낸다</li>
 *   <li><b>조건부 판정</b> — 조건 없이 "조건부"라고 하면 보류로 강등한다</li>
 *   <li><b>신뢰도</b> — 자막이 하나도 없는데 "높음"이면 "중간"으로 낮춘다</li>
 * </ol>
 */
@Slf4j
public final class AiReviewValidator {

    /** 근거가 무너진 항목의 대체 점수 비율. */
    private static final double NEUTRAL_RATIO = 0.6;

    private AiReviewValidator() {
    }

    /**
     * @param sources        인용 대조에 쓸 원문(자막·댓글)
     * @param hasTranscript  자막이 하나라도 있는가 — 신뢰도 하향 판단에 쓴다
     */
    public static AiReviewResult validate(AiReviewResult raw, List<String> sources,
                                          boolean hasTranscript, AiScorePoints points) {

        List<Risk> risks = raw.risks().stream()
                .map(risk -> new Risk(risk.claim(), risk.basis(),
                        grounded(risk.evidence(), sources), risk.severity()))
                // 근거가 하나도 남지 않은 위험 주장은 통째로 버린다 — 확인할 수 없는 의혹을
                // 화면에 띄우면 사람이 근거 없이 사람을 판단하게 된다.
                .filter(risk -> !risk.evidence().isEmpty())
                .toList();

        ScoredItem brandSafety = check(raw.brandSafety(), sources, points.brandSafety());
        ScoredItem campaignFit = check(raw.campaignFit(), sources, points.campaignFit());
        ScoredItem audienceQuality = check(raw.audienceQuality(), sources, points.audienceQuality());

        Verdict verdict = raw.verdict();
        if (verdict.recommend() == Recommendation.CONDITIONAL
                && (verdict.conditions() == null || verdict.conditions().isEmpty())) {
            log.debug("조건 없는 조건부 판정 — 보류로 강등");
            verdict = new Verdict(verdict.headline(), Recommendation.HOLD, List.of());
        }

        Confidence confidence = raw.confidence();
        if (!hasTranscript && confidence == Confidence.HIGH) {
            confidence = Confidence.MEDIUM;
        }
        // 위험 신호가 큰데 안전성이 만점이면 판단이 어긋난 것이다. 점수는 유지하되
        // 신뢰도를 낮춰 사람이 직접 확인하도록 유도한다.
        if (hasHighRisk(risks) && brandSafety.score() >= points.brandSafety()) {
            log.info("높은 위험 신호와 만점 안전성이 충돌 — 신뢰도 하향");
            confidence = Confidence.LOW;
        }

        return new AiReviewResult(risks, brandSafety, campaignFit, audienceQuality, verdict,
                confidence);
    }

    private static ScoredItem check(ScoredItem item, List<String> sources, double max) {
        List<Evidence> kept = grounded(item.evidence(), sources);

        if (kept.isEmpty()) {
            log.info("근거 검증 실패 — 항목 점수를 중립값으로 대체");
            return new ScoredItem(max * NEUTRAL_RATIO, item.reasons(), List.of(), true);
        }
        double score = Math.clamp(item.score(), 0, max);
        return new ScoredItem(score, item.reasons(), kept, false);
    }

    private static List<Evidence> grounded(List<Evidence> evidence, List<String> sources) {
        if (evidence == null) {
            return List.of();
        }
        return evidence.stream()
                .filter(item -> QuoteVerifier.isGrounded(item.quote(), sources))
                .toList();
    }

    private static boolean hasHighRisk(List<Risk> risks) {
        return risks.stream().anyMatch(risk -> risk.severity() == Severity.HIGH);
    }
}
