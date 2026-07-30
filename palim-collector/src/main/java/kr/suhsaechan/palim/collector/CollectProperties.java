package kr.suhsaechan.palim.collector;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 수집 동작 설정.
 *
 * @param overlap          조회 구간 겹침 여유
 * @param initialLookback  커서가 없는 첫 수집에서 거슬러 올라갈 범위
 * @param failureThreshold 이 횟수만큼 연속 실패하면 경고 후 채널을 자동 비활성화한다
 */
@ConfigurationProperties(prefix = "palim.collect")
public record CollectProperties(
        Duration overlap,
        Duration initialLookback,
        int failureThreshold
) {

    private static final Duration DEFAULT_OVERLAP = Duration.ofMinutes(10);
    private static final Duration DEFAULT_INITIAL_LOOKBACK = Duration.ofHours(24);
    private static final int DEFAULT_FAILURE_THRESHOLD = 3;

    public CollectProperties {
        overlap = overlap != null ? overlap : DEFAULT_OVERLAP;
        initialLookback = initialLookback != null ? initialLookback : DEFAULT_INITIAL_LOOKBACK;
        failureThreshold = failureThreshold > 0 ? failureThreshold : DEFAULT_FAILURE_THRESHOLD;
    }
}
