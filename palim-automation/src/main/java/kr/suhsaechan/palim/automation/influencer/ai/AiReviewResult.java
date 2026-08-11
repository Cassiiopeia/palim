package kr.suhsaechan.palim.automation.influencer.ai;

import java.util.List;

/**
 * AI 심층 심사 결과.
 *
 * <p>{@link #risks} 가 첫 필드인 것은 의도다. 구조화 출력은 순서대로 생성되므로 <b>반대 논거가
 * 점수보다 먼저 쓰이고</b>, 그 내용이 뒤따르는 점수에 반영된다. 점수를 먼저 매기게 하면 AI 는
 * 그 점수를 정당화하는 방향으로 근거를 고른다.
 */
public record AiReviewResult(
        List<Risk> risks,
        ScoredItem brandSafety,
        ScoredItem campaignFit,
        ScoredItem audienceQuality,
        Verdict verdict,
        Confidence confidence) {

    /** 근거의 성격. 추론을 사실처럼 제시하지 못하게 한다. */
    public enum Basis {
        /** 원문에 그대로 있다. */
        OBSERVED,
        /** 원문에서 추론했다. 화면이 "추측" 배지를 붙인다. */
        INFERRED
    }

    public enum Severity {
        LOW, MEDIUM, HIGH
    }

    /** 자료가 부족하면 AI 가 스스로 낮춰 신고한다 — 사람이 얼마나 믿고 읽을지의 기준이다. */
    public enum Confidence {
        LOW, MEDIUM, HIGH
    }

    public enum Recommendation {
        PROPOSE, CONDITIONAL, HOLD, AVOID
    }

    public enum EvidenceSource {
        TRANSCRIPT, COMMENT, METADATA
    }

    /** 원문 인용. 검증에 실패하면 코드가 버린다. */
    public record Evidence(EvidenceSource source, String quote) {
    }

    public record Risk(String claim, Basis basis, List<Evidence> evidence, Severity severity) {
    }

    /**
     * 항목 점수.
     *
     * @param groundingFailed 근거가 모두 검증에 실패해 중립값으로 대체됐다 — 화면이 표시한다
     */
    public record ScoredItem(double score, List<String> reasons, List<Evidence> evidence,
                             boolean groundingFailed) {

        public ScoredItem withScore(double score, boolean groundingFailed) {
            return new ScoredItem(score, reasons, evidence, groundingFailed);
        }

        public ScoredItem withEvidence(List<Evidence> evidence) {
            return new ScoredItem(score, reasons, evidence, groundingFailed);
        }
    }

    public record Verdict(String headline, Recommendation recommend, List<String> conditions) {
    }

    /** AI 30점 합계. */
    public double total() {
        return brandSafety.score() + campaignFit.score() + audienceQuality.score();
    }
}
