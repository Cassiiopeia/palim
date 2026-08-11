package kr.suhsaechan.palim.automation.influencer.ai;

/**
 * AI 심사 호출 경계.
 *
 * <p>인터페이스로 분리해 통합 테스트가 실제 호출 없이 고정 응답으로 파이프라인을 검증하게 한다 —
 * AI 호출은 비용이 들고, 같은 입력에도 응답이 미세하게 달라 테스트가 불안정해진다.
 */
public interface InfluencerAiClient {

    /**
     * @return 검증 전 원본 결과. 인용 검증·범위 보정은 {@link AiReviewValidator} 가 한다
     */
    AiReviewResult review(ReviewInput input, AiScorePoints points);
}
