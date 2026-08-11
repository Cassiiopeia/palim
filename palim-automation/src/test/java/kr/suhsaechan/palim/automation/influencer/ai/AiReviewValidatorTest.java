package kr.suhsaechan.palim.automation.influencer.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Basis;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Confidence;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Evidence;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.EvidenceSource;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Recommendation;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Risk;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.ScoredItem;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Severity;
import kr.suhsaechan.palim.automation.influencer.ai.AiReviewResult.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiReviewValidatorTest {

    private static final AiScorePoints POINTS = new AiScorePoints(12, 10, 8);

    private static final List<String> SOURCES = List.of(
            "이 영상은 협찬을 받아 제작했습니다 오늘은 캠핑 요리를 해볼게요",
            "저 코펠 어디 제품인가요 링크 좀요");

    private static Evidence real() {
        return new Evidence(EvidenceSource.TRANSCRIPT, "협찬을 받아 제작했습니다");
    }

    private static Evidence fake() {
        return new Evidence(EvidenceSource.COMMENT, "구독자를 돈 주고 샀다는 의혹이 있습니다");
    }

    private static ScoredItem item(double score, Evidence... evidence) {
        return new ScoredItem(score, List.of("사유"), List.of(evidence), false);
    }

    private static AiReviewResult raw(List<Risk> risks, ScoredItem safety, Verdict verdict,
                                      Confidence confidence) {
        return new AiReviewResult(risks, safety, item(9, real()), item(7, real()), verdict,
                confidence);
    }

    private static Verdict propose() {
        return new Verdict("요약", Recommendation.PROPOSE, List.of());
    }

    @Test
    @DisplayName("지어낸 근거는 버려진다")
    void 지어낸_근거_제거() {
        AiReviewResult result = AiReviewValidator.validate(
                raw(List.of(), item(11, real(), fake()), propose(), Confidence.HIGH),
                SOURCES, true, POINTS);

        assertThat(result.brandSafety().evidence()).hasSize(1);
        assertThat(result.brandSafety().score()).isEqualTo(11);
        assertThat(result.brandSafety().groundingFailed()).isFalse();
    }

    @Test
    @DisplayName("근거가 모두 무너지면 점수는 중립값이 된다 — 0점도 만점도 아니다")
    void 근거_전멸시_중립값() {
        AiReviewResult result = AiReviewValidator.validate(
                raw(List.of(), item(12, fake()), propose(), Confidence.HIGH),
                SOURCES, true, POINTS);

        // 만점 12 × 0.6 = 7.2
        assertThat(result.brandSafety().score()).isCloseTo(7.2, within(0.01));
        assertThat(result.brandSafety().groundingFailed()).isTrue();
        assertThat(result.brandSafety().evidence()).isEmpty();
    }

    @Test
    @DisplayName("근거 없는 위험 주장은 통째로 사라진다 — 확인 불가한 의혹을 띄우지 않는다")
    void 근거없는_위험_제거() {
        Risk grounded = new Risk("광고 고지를 명시한다", Basis.OBSERVED, List.of(real()), Severity.LOW);
        Risk ungrounded = new Risk("논란이 있다", Basis.INFERRED, List.of(fake()), Severity.HIGH);

        AiReviewResult result = AiReviewValidator.validate(
                raw(List.of(grounded, ungrounded), item(10, real()), propose(), Confidence.HIGH),
                SOURCES, true, POINTS);

        assertThat(result.risks()).hasSize(1);
        assertThat(result.risks().getFirst().claim()).isEqualTo("광고 고지를 명시한다");
    }

    @Test
    @DisplayName("배점을 넘는 점수는 잘린다")
    void 점수_범위_클램프() {
        AiReviewResult result = AiReviewValidator.validate(
                raw(List.of(), item(99, real()), propose(), Confidence.HIGH),
                SOURCES, true, POINTS);

        assertThat(result.brandSafety().score()).isEqualTo(12);
    }

    @Test
    @DisplayName("조건 없는 조건부 판정은 보류로 강등된다")
    void 조건부_강등() {
        Verdict conditional = new Verdict("요약", Recommendation.CONDITIONAL, List.of());

        AiReviewResult result = AiReviewValidator.validate(
                raw(List.of(), item(10, real()), conditional, Confidence.HIGH),
                SOURCES, true, POINTS);

        assertThat(result.verdict().recommend()).isEqualTo(Recommendation.HOLD);
    }

    @Test
    @DisplayName("자막이 없으면 신뢰도 '높음'을 인정하지 않는다")
    void 자막_없으면_신뢰도_하향() {
        AiReviewResult result = AiReviewValidator.validate(
                raw(List.of(), item(10, real()), propose(), Confidence.HIGH),
                SOURCES, false, POINTS);

        assertThat(result.confidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    @DisplayName("높은 위험 신호와 만점 안전성이 충돌하면 신뢰도를 낮춰 사람 확인을 유도한다")
    void 판단_충돌_감지() {
        Risk highRisk = new Risk("해명 요구가 몰려 있다", Basis.OBSERVED,
                List.of(new Evidence(EvidenceSource.COMMENT, "저 코펠 어디 제품인가요")),
                Severity.HIGH);

        AiReviewResult result = AiReviewValidator.validate(
                raw(List.of(highRisk), item(12, real()), propose(), Confidence.HIGH),
                SOURCES, true, POINTS);

        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
        // 점수는 유지한다 — 판단은 사람이 한다
        assertThat(result.brandSafety().score()).isEqualTo(12);
    }

    @Test
    @DisplayName("AI 총점은 세 항목의 합이다")
    void 총점() {
        AiReviewResult result = AiReviewValidator.validate(
                raw(List.of(), item(12, real()), propose(), Confidence.HIGH),
                SOURCES, true, POINTS);

        assertThat(result.total()).isEqualTo(12 + 9 + 7);
    }
}
