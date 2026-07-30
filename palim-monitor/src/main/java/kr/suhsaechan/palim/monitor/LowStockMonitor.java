package kr.suhsaechan.palim.monitor;

import java.time.Duration;
import java.util.List;
import kr.suhsaechan.palim.notification.NotificationSetting;
import kr.suhsaechan.palim.notification.NotificationSettingService;
import kr.suhsaechan.palim.notification.NotificationType;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.notification.payload.LowStockPayload;
import kr.suhsaechan.palim.notification.payload.OutOfStockPayload;
import kr.suhsaechan.palim.sku.Sku;
import kr.suhsaechan.palim.sku.SkuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 안전재고 미달과 품절을 감시한다 (F-05).
 *
 * <h2>재알림 억제가 이 배치의 핵심이다</h2>
 *
 * <p>재고 부족은 <b>입고될 때까지 계속 부족한 상태로 남는다.</b> 매 주기마다 알림을 등록하면
 * 30분마다 같은 메시지가 나가고, 발주자는 알림을 아예 보지 않게 된다. 그러면 이 시스템의
 * 존재 이유가 무너진다.
 *
 * <p>F-05 가 "반복 알림 주기"를 설정 항목으로 둔 이유이며, 기본값은 1일 1회다. 억제 판단은
 * {@code OutboxService.enqueueIfNotRecent} 가 Outbox 의 등록 이력으로 처리한다.
 *
 * <h2>품절과 안전재고 경고를 함께 보내지 않는다</h2>
 *
 * <p>재고가 0이면 안전재고 조건({@code quantity < threshold})도 당연히 만족한다. 둘 다 보내면
 * 같은 SKU 로 두 개의 알림이 나가므로, <b>품절이면 품절 알림만</b> 보낸다.
 *
 * <p>품절은 긴급 알림이라 야간 보류·묶음에서 제외된다. 판매가 계속되면 오버셀링으로 이어지기
 * 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LowStockMonitor {

    /** 소진 예상일 계산에 쓰는 판매 이력 기간. */
    private static final int SALES_WINDOW_DAYS = 7;

    private final SkuService skuService;
    private final NotificationSettingService notificationSettingService;
    private final OutboxService outboxService;

    /**
     * 안전재고 미달 SKU 를 찾아 알림을 등록한다.
     *
     * @return 알림을 등록한 SKU 수 (억제된 것은 세지 않는다)
     */
    @Transactional
    @Scheduled(fixedDelayString = "${palim.monitor.low-stock-delay:PT30M}")
    public int monitor() {
        List<Sku> targets = skuService.findBelowThreshold();
        if (targets.isEmpty()) {
            return 0;
        }

        NotificationSetting setting = notificationSettingService.get();
        Duration repeatInterval = Duration.ofHours(setting.getLowStockRepeatHours());

        int enqueued = 0;
        for (Sku sku : targets) {
            boolean registered = sku.isOutOfStock()
                    ? enqueueOutOfStock(sku, repeatInterval)
                    : enqueueLowStock(sku, repeatInterval);
            if (registered) {
                enqueued++;
            }
        }

        log.info("안전재고 감시 — 미달 {}건, 알림 등록 {}건 (재알림 주기 {}시간)",
                targets.size(), enqueued, setting.getLowStockRepeatHours());
        return enqueued;
    }

    private boolean enqueueOutOfStock(Sku sku, Duration repeatInterval) {
        return outboxService.enqueueIfNotRecent(
                NotificationType.OUT_OF_STOCK,
                "OUT_OF_STOCK:" + sku.getCode(),
                repeatInterval,
                new OutOfStockPayload(sku.getCode(), sku.getName())).isPresent();
    }

    private boolean enqueueLowStock(Sku sku, Duration repeatInterval) {
        double averageDailySales = skuService.averageDailySales(sku.getId(), SALES_WINDOW_DAYS);

        return outboxService.enqueueIfNotRecent(
                NotificationType.LOW_STOCK,
                "LOW_STOCK:" + sku.getCode(),
                repeatInterval,
                new LowStockPayload(sku.getCode(), sku.getName(), sku.getQuantity(),
                        sku.getSafetyThreshold(), averageDailySales)).isPresent();
    }
}
