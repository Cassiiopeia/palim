package kr.suhsaechan.palim.notification.payload;

import java.time.Instant;
import java.util.List;

/**
 * 라이징 인플루언서 주간 요약 (#43).
 *
 * <p>채널명과 지표만 담는다 — 알림은 "지금 봐야 할 것이 있다"를 알리는 용도이고, 판단에 필요한
 * 근거는 화면에 있다. 메시지에 모든 정보를 넣으면 길어져서 읽히지 않는다.
 *
 * @param detectedCount 이번 주 신규 감지 수
 * @param activeCount   현재 레이더에 올라 있는 전체 수
 * @param channels      상위 채널(메시지 길이 때문에 잘라서 넘긴다)
 * @param periodStart   집계 시작 시각
 */
public record RisingInfluencerPayload(
        int detectedCount,
        long activeCount,
        List<RisingChannel> channels,
        Instant periodStart
) {

    /**
     * @param arbitrageRatio 규모 대비 몇 배로 도는가 — 사장님이 판단하는 숫자
     * @param daysSinceDetected 감지 후 경과일. 오래될수록 이미 단가가 올랐을 가능성이 크다
     */
    public record RisingChannel(
            String title,
            long subscriberCount,
            long medianViews,
            double risingScore,
            double arbitrageRatio,
            long daysSinceDetected
    ) {
    }
}
