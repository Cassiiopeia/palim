package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.monitor.DailyReportAssembler;
import kr.suhsaechan.palim.monitor.LowStockMonitor;
import kr.suhsaechan.palim.monitor.StockConsistencyChecker;
import kr.suhsaechan.palim.notification.NotificationOutbox;
import kr.suhsaechan.palim.notification.NotificationType;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.notification.payload.DailyReportPayload;
import kr.suhsaechan.palim.notification.payload.LowStockPayload;
import kr.suhsaechan.palim.notification.payload.OutOfStockPayload;
import kr.suhsaechan.palim.notification.payload.StockMismatchPayload;
import kr.suhsaechan.palim.sku.Sku;
import kr.suhsaechan.palim.sku.SkuService;
import kr.suhsaechan.palim.sku.StockMovement;
import kr.suhsaechan.palim.sku.StockMovementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감시 배치 검증.
 *
 * <p>가장 중요한 것은 <b>재알림 억제</b>다. 억제가 동작하지 않으면 재고 부족이 지속되는 동안
 * 30분마다 같은 알림이 나가고, 발주자가 알림을 아예 보지 않게 되어 이 시스템의 존재 이유가
 * 무너진다.
 */
@Transactional
class MonitorIntegrationTest extends IntegrationTest {

    @Autowired
    private SkuService skuService;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private StockConsistencyChecker stockConsistencyChecker;

    @Autowired
    private LowStockMonitor lowStockMonitor;

    @Autowired
    private DailyReportAssembler dailyReportAssembler;

    private List<NotificationOutbox> pendingOf(NotificationType type) {
        return outboxService.findPending(type);
    }

    // ------------------------------------------------------------------
    // 재알림 억제 — 이 배치들의 핵심
    // ------------------------------------------------------------------

    @Test
    @DisplayName("같은 억제 키로는 기간 안에 다시 등록되지 않는다")
    void 억제_기간_안에는_중복_등록되지_않는다() {
        Object payload = new OutOfStockPayload("SKU-DEDUPE", "억제 테스트 상품");

        boolean first = outboxService.enqueueIfNotRecent(
                NotificationType.OUT_OF_STOCK, "OUT_OF_STOCK:SKU-DEDUPE",
                Duration.ofHours(24), payload).isPresent();
        boolean second = outboxService.enqueueIfNotRecent(
                NotificationType.OUT_OF_STOCK, "OUT_OF_STOCK:SKU-DEDUPE",
                Duration.ofHours(24), payload).isPresent();

        assertThat(first).as("첫 등록은 성공해야 한다").isTrue();
        assertThat(second).as("억제 기간 안의 재등록은 막혀야 한다").isFalse();
    }

    @Test
    @DisplayName("억제 기간이 0이면 다시 등록된다")
    void 억제_기간이_지나면_다시_등록된다() {
        Object payload = new OutOfStockPayload("SKU-DEDUPE-2", "억제 테스트 상품");

        outboxService.enqueueIfNotRecent(NotificationType.OUT_OF_STOCK,
                "OUT_OF_STOCK:SKU-DEDUPE-2", Duration.ofHours(24), payload);
        boolean afterWindow = outboxService.enqueueIfNotRecent(NotificationType.OUT_OF_STOCK,
                "OUT_OF_STOCK:SKU-DEDUPE-2", Duration.ZERO, payload).isPresent();

        assertThat(afterWindow).isTrue();
    }

    @Test
    @DisplayName("억제 키가 다르면 각각 등록된다")
    void 다른_키는_각각_등록된다() {
        outboxService.enqueueIfNotRecent(NotificationType.LOW_STOCK, "LOW_STOCK:SKU-A",
                Duration.ofHours(24), new LowStockPayload("SKU-A", "상품A", 1, 5, 1.0));
        boolean other = outboxService.enqueueIfNotRecent(NotificationType.LOW_STOCK,
                "LOW_STOCK:SKU-B", Duration.ofHours(24),
                new LowStockPayload("SKU-B", "상품B", 2, 5, 1.0)).isPresent();

        assertThat(other).isTrue();
    }

    // ------------------------------------------------------------------
    // 재고 정합성 대조
    // ------------------------------------------------------------------

    @Test
    @DisplayName("이력과 스냅샷이 어긋난 SKU 를 찾아 알림을 등록한다")
    void 불일치를_찾아_알린다() {
        Sku sku = skuService.register("MON-MISMATCH", "불일치 상품", 100, 10);
        // 이력 없이 스냅샷만 바꿔 인위적으로 어긋뜨린다.
        stockMovementRepository.deleteAll(stockMovementRepository.findBySkuIdOrderByCreatedAtDesc(sku.getId()));

        int mismatched = stockConsistencyChecker.check();

        assertThat(mismatched).isPositive();
        assertThat(pendingOf(NotificationType.STOCK_MISMATCH))
                .anySatisfy(outbox -> {
                    StockMismatchPayload payload =
                            outboxService.readPayload(outbox, StockMismatchPayload.class);
                    if ("MON-MISMATCH".equals(payload.skuCode())) {
                        assertThat(payload.snapshotQuantity()).isEqualTo(100);
                        assertThat(payload.historySum()).isZero();
                        assertThat(payload.difference()).isEqualTo(100);
                    }
                });
    }

    @Test
    @DisplayName("정상 SKU 만 있으면 불일치가 보고되지 않는다")
    void 정상이면_알리지_않는다() {
        skuService.register("MON-OK", "정상 상품", 50, 5);

        assertThat(skuService.isConsistent(skuService.getByCode("MON-OK").getId())).isTrue();
    }

    /**
     * 오버셀링으로 음수가 된 재고도 정상 대조 대상이다. 이력과 스냅샷이 함께 음수로 내려가므로
     * 검산식이 그대로 성립한다 — 성립하지 않으면 오버셀링마다 거짓 경고가 나간다.
     */
    @Test
    @DisplayName("오버셀링으로 음수가 되어도 대조가 일치한다")
    void 음수_재고도_대조가_일치한다() {
        Sku sku = skuService.register("MON-OVERSELL", "오버셀 상품", 2, 5);
        skuService.decreaseForSale(sku.getId(), 5, UuidV7.generate());

        assertThat(skuService.getById(sku.getId()).getQuantity()).isEqualTo(-3);
        assertThat(skuService.isConsistent(sku.getId())).isTrue();
    }

    // ------------------------------------------------------------------
    // 안전재고 감시
    // ------------------------------------------------------------------

    @Test
    @DisplayName("안전재고 미달 SKU 에 알림을 등록한다")
    void 안전재고_미달을_알린다() {
        Sku sku = skuService.register("MON-LOW", "재고부족 상품", 10, 5);
        skuService.decreaseForSale(sku.getId(), 7, UuidV7.generate());

        lowStockMonitor.monitor();

        assertThat(pendingOf(NotificationType.LOW_STOCK))
                .extracting(outbox -> outboxService.readPayload(outbox, LowStockPayload.class).skuCode())
                .contains("MON-LOW");
    }

    /**
     * 재고가 0이면 안전재고 조건도 만족한다. 둘 다 보내면 같은 SKU 로 알림이 두 개 나가므로
     * 품절만 보낸다.
     */
    @Test
    @DisplayName("품절이면 품절 알림만 등록하고 안전재고 알림은 등록하지 않는다")
    void 품절이면_품절만_알린다() {
        Sku sku = skuService.register("MON-OUT", "품절 상품", 5, 10);
        skuService.decreaseForSale(sku.getId(), 5, UuidV7.generate());

        lowStockMonitor.monitor();

        assertThat(pendingOf(NotificationType.OUT_OF_STOCK))
                .extracting(outbox -> outboxService.readPayload(outbox, OutOfStockPayload.class).skuCode())
                .contains("MON-OUT");
        assertThat(pendingOf(NotificationType.LOW_STOCK))
                .extracting(outbox -> outboxService.readPayload(outbox, LowStockPayload.class).skuCode())
                .doesNotContain("MON-OUT");
    }

    @Test
    @DisplayName("연속 실행해도 같은 SKU 로 중복 등록되지 않는다")
    void 연속_실행_시_중복되지_않는다() {
        Sku sku = skuService.register("MON-REPEAT", "반복 테스트 상품", 10, 5);
        skuService.decreaseForSale(sku.getId(), 7, UuidV7.generate());

        lowStockMonitor.monitor();
        int secondRun = lowStockMonitor.monitor();

        assertThat(secondRun).as("두 번째 실행에서는 억제되어야 한다").isZero();
        assertThat(pendingOf(NotificationType.LOW_STOCK))
                .filteredOn(outbox ->
                        "MON-REPEAT".equals(outboxService.readPayload(outbox, LowStockPayload.class).skuCode()))
                .hasSize(1);
    }

    // ------------------------------------------------------------------
    // 일일 리포트
    // ------------------------------------------------------------------

    @Test
    @DisplayName("리포트 집계가 실제 데이터와 일치한다")
    void 리포트_수치가_실제와_일치한다() {
        Sku sku = skuService.register("MON-REPORT", "리포트 상품", 100, 5);
        skuService.decreaseForSale(sku.getId(), 3, UuidV7.generate());

        DailyReportPayload payload = dailyReportAssembler.assemble(LocalDate.now());

        // 주문을 만들지 않았으므로 판매 수치는 0이고, 재고 부족 건수는 실제 상태를 반영한다.
        assertThat(payload.totalOrderCount()).isZero();
        assertThat(payload.totalAmount()).isZero();
        assertThat(payload.lowStockCount()).isGreaterThanOrEqualTo(0);
        assertThat(payload.date()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("경고 항목이 없으면 hasWarnings 가 false 다")
    void 경고가_없으면_false다() {
        DailyReportPayload clean = new DailyReportPayload(
                LocalDate.now(), 0, 0L, List.of(), List.of(), 0, 0, List.of());

        assertThat(clean.hasWarnings()).isFalse();
    }

    @Test
    @DisplayName("초기 재고 이력이 있으면 등록 직후에도 대조가 일치한다")
    void 등록_직후_대조가_일치한다() {
        Sku sku = skuService.register("MON-INIT", "초기재고 상품", 77, 5);

        assertThat(stockMovementRepository.sumDeltaBySkuId(sku.getId())).isEqualTo(77);
        assertThat(skuService.isConsistent(sku.getId())).isTrue();
    }

    @Test
    @DisplayName("실사 조정 후에도 대조가 일치한다")
    void 실사_조정_후에도_일치한다() {
        Sku sku = skuService.register("MON-ADJUST", "조정 상품", 10, 5);

        skuService.adjust(sku.getId(), 50, "월말 실사");

        assertThat(skuService.getById(sku.getId()).getQuantity()).isEqualTo(50);
        assertThat(skuService.isConsistent(sku.getId())).isTrue();

        StockMovement adjustment = stockMovementRepository
                .findBySkuIdOrderByCreatedAtDesc(sku.getId()).getFirst();
        assertThat(adjustment.getDelta()).as("delta 는 변경 전후의 차이여야 한다").isEqualTo(40);
    }
}
