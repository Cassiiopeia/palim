package kr.suhsaechan.palim.automation.influencer.ai;

/**
 * AI 항목별 배점. 합이 30이 되도록 설정에서 관리한다.
 *
 * <p>브랜드 안전성이 가장 큰 이유는 실패 비용의 비대칭 때문이다 — 적합도를 잘못 봐서 반응이
 * 미지근한 것과, 논란 중인 채널에 광고를 걸어 브랜드가 같이 언급되는 것은 손해의 크기가 다르다.
 */
public record AiScorePoints(double brandSafety, double campaignFit, double audienceQuality) {

    public double total() {
        return brandSafety + campaignFit + audienceQuality;
    }
}
