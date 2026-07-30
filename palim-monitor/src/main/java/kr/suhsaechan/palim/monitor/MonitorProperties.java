package kr.suhsaechan.palim.monitor;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 감시 배치 설정.
 *
 * @param stockConsistencyDelay 재고 정합성 대조 주기
 * @param lowStockDelay         안전재고 감시 주기
 * @param dailyReportDelay      일일 리포트 발송 시각 확인 주기
 * @param mismatchAlertInterval 같은 SKU 의 정합성 불일치 재알림 간격
 * @param topSkuLimit           일일 리포트의 판매 상위 표시 개수
 */
@ConfigurationProperties(prefix = "palim.monitor")
public record MonitorProperties(
        Duration stockConsistencyDelay,
        Duration lowStockDelay,
        Duration dailyReportDelay,
        Duration mismatchAlertInterval,
        int topSkuLimit
) {

    private static final Duration DEFAULT_STOCK_CONSISTENCY_DELAY = Duration.ofHours(24);
    private static final Duration DEFAULT_LOW_STOCK_DELAY = Duration.ofMinutes(30);
    private static final Duration DEFAULT_DAILY_REPORT_DELAY = Duration.ofMinutes(5);
    private static final Duration DEFAULT_MISMATCH_ALERT_INTERVAL = Duration.ofHours(24);
    private static final int DEFAULT_TOP_SKU_LIMIT = 3;

    public MonitorProperties {
        stockConsistencyDelay = stockConsistencyDelay != null
                ? stockConsistencyDelay : DEFAULT_STOCK_CONSISTENCY_DELAY;
        lowStockDelay = lowStockDelay != null ? lowStockDelay : DEFAULT_LOW_STOCK_DELAY;
        dailyReportDelay = dailyReportDelay != null ? dailyReportDelay : DEFAULT_DAILY_REPORT_DELAY;
        mismatchAlertInterval = mismatchAlertInterval != null
                ? mismatchAlertInterval : DEFAULT_MISMATCH_ALERT_INTERVAL;
        topSkuLimit = topSkuLimit > 0 ? topSkuLimit : DEFAULT_TOP_SKU_LIMIT;
    }
}
