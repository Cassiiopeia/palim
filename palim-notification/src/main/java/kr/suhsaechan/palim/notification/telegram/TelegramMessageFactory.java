package kr.suhsaechan.palim.notification.telegram;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import kr.suhsaechan.palim.notification.NotificationOutbox;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.notification.payload.CollectFailurePayload;
import kr.suhsaechan.palim.notification.payload.DailyReportPayload;
import kr.suhsaechan.palim.notification.payload.LowStockPayload;
import kr.suhsaechan.palim.notification.payload.NewOrderPayload;
import kr.suhsaechan.palim.notification.payload.OutOfStockPayload;
import kr.suhsaechan.palim.notification.payload.OverSellPayload;
import kr.suhsaechan.palim.notification.payload.StockMismatchPayload;
import kr.suhsaechan.palim.notification.payload.StockPushFailurePayload;
import kr.suhsaechan.palim.notification.payload.UnmappedProductPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Outbox 를 텔레그램 메시지 문구로 조립한다.
 *
 * <p>알림 종류별로 클래스를 나누지 않는다. 종류가 늘어도 여기 case 하나만 추가하면 되고,
 * 전체 문구를 한 파일에서 비교할 수 있어 형식 일관성을 유지하기 쉽다.
 *
 * <p>시각은 <b>KST 로 변환해 표시</b>한다. 저장은 {@code Instant}(UTC)로 하지만 발주자가 읽는
 * 문구에 UTC 시각이 나가면 주문 시각을 오해한다.
 */
@Component
@RequiredArgsConstructor
public class TelegramMessageFactory {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("MM-dd");

    private final OutboxService outboxService;

    public String create(NotificationOutbox outbox) {
        return switch (outbox.getType()) {
            case NEW_ORDER -> newOrder(outboxService.readPayload(outbox, NewOrderPayload.class));
            case UNMAPPED_PRODUCT ->
                    unmapped(outboxService.readPayload(outbox, UnmappedProductPayload.class));
            case OVERSELL -> oversell(outboxService.readPayload(outbox, OverSellPayload.class));
            case LOW_STOCK -> lowStock(outboxService.readPayload(outbox, LowStockPayload.class));
            case OUT_OF_STOCK ->
                    outOfStock(outboxService.readPayload(outbox, OutOfStockPayload.class));
            case COLLECT_FAILURE ->
                    collectFailure(outboxService.readPayload(outbox, CollectFailurePayload.class));
            case STOCK_PUSH_FAILURE ->
                    stockPushFailure(outboxService.readPayload(outbox, StockPushFailurePayload.class));
            case STOCK_MISMATCH ->
                    stockMismatch(outboxService.readPayload(outbox, StockMismatchPayload.class));
            case DAILY_REPORT ->
                    dailyReport(outboxService.readPayload(outbox, DailyReportPayload.class));
        };
    }

    /**
     * 주문 알림을 하나의 메시지로 묶는다 (F-02 묶음 발송).
     *
     * <p>주문량이 많을 때 알림 과다로 아예 확인하지 않게 되는 문제를 막기 위한 기능이다.
     */
    public String createBatched(List<NotificationOutbox> outboxes) {
        if (outboxes.size() == 1) {
            return create(outboxes.getFirst());
        }

        List<NewOrderPayload> orders = outboxes.stream()
                .map(outbox -> outboxService.readPayload(outbox, NewOrderPayload.class))
                .toList();

        int totalQuantity = orders.stream().mapToInt(NewOrderPayload::quantity).sum();
        long totalAmount = orders.stream().mapToLong(NewOrderPayload::amount).sum();

        StringBuilder builder = new StringBuilder()
                .append("🛒 신규 주문 ").append(orders.size()).append("건\n\n");

        for (NewOrderPayload order : orders) {
            builder.append("· ").append(order.channelName())
                    .append(" [").append(order.skuCode()).append("] ")
                    .append(order.productName())
                    .append(" ").append(order.quantity()).append("개")
                    .append(" / ").append(money(order.amount()))
                    .append("\n  잔여 ").append(order.currentStock()).append("개\n");
        }

        return builder.append("\n합계 : ").append(totalQuantity).append("개 / ")
                .append(money(totalAmount))
                .toString();
    }

    // ------------------------------------------------------------------

    private String newOrder(NewOrderPayload payload) {
        return """
                🛒 신규 주문

                채널   : %s
                상품   : [%s] %s
                수량   : %d개
                금액   : %s
                주문시각: %s

                현재 재고: %d개"""
                .formatted(payload.channelName(), payload.skuCode(), payload.productName(),
                        payload.quantity(), money(payload.amount()),
                        timestamp(payload.orderedAt()), payload.currentStock());
    }

    private String unmapped(UnmappedProductPayload payload) {
        return """
                ❓ 매핑되지 않은 상품

                채널   : %s
                주문번호: %s
                상품코드: %s%s
                상품명 : %s
                수량   : %d개

                이 주문은 저장되었으나 재고에 반영되지 않았습니다.
                웹에서 상품을 SKU와 연결하면 자동으로 반영됩니다."""
                .formatted(payload.channelName(), payload.channelOrderNo(),
                        payload.channelProductNo(),
                        payload.channelOptionNo() != null ? " / " + payload.channelOptionNo() : "",
                        payload.channelProductName(), payload.quantity());
    }

    private String oversell(OverSellPayload payload) {
        return """
                🚨 초과판매 발생

                [%s] %s
                현재 재고: %d개 (부족 %d개)

                채널   : %s
                주문번호: %s
                수량   : %d개

                출고 가능 수량을 초과했습니다. 재고 확보나 주문 취소가 필요합니다."""
                .formatted(payload.skuCode(), payload.productName(),
                        payload.currentStock(), payload.shortageQuantity(),
                        payload.channelName(), payload.channelOrderNo(), payload.quantity());
    }

    private String lowStock(LowStockPayload payload) {
        String forecast = payload.expectedDaysLeft() != null
                ? "예상 소진: 약 %.1f일 후".formatted(payload.expectedDaysLeft())
                : "최근 판매 없음";

        return """
                ⚠️ 재고 부족

                [%s] %s
                현재 재고: %d개 (임계치: %d개)

                최근 7일 평균 판매: %.1f개/일
                %s"""
                .formatted(payload.skuCode(), payload.productName(),
                        payload.currentStock(), payload.safetyThreshold(),
                        payload.averageDailySales(), forecast);
    }

    private String outOfStock(OutOfStockPayload payload) {
        return """
                ❌ 품절

                [%s] %s
                재고가 0개입니다.

                판매가 계속되면 초과판매로 이어집니다."""
                .formatted(payload.skuCode(), payload.productName());
    }

    private String collectFailure(CollectFailurePayload payload) {
        String suffix = payload.autoDisabled()
                ? "\n\n연속 실패로 해당 채널 수집을 중단했습니다.\n인증정보와 IP 등록을 확인한 뒤 웹에서 다시 활성화하세요."
                : "\n\n다음 주기에 재시도합니다.";

        return """
                ❗ 채널 수집 실패

                채널   : %s
                연속 실패: %d회
                시각   : %s

                사유 : %s%s"""
                .formatted(payload.channelName(), payload.consecutiveFailureCount(),
                        timestamp(payload.attemptedAt()), payload.errorMessage(), suffix);
    }

    private String stockPushFailure(StockPushFailurePayload payload) {
        String header = payload.blocked() ? "🛑 재고 전송 차단" : "❗ 재고 전송 실패";
        String before = payload.beforeQuantity() != null
                ? payload.beforeQuantity() + "개"
                : "확인 불가";

        return """
                %s

                채널   : %s
                상품   : [%s] %s
                전송 전 : %s
                전송 시도: %d개

                사유 : %s"""
                .formatted(header, payload.channelName(), payload.skuCode(), payload.productName(),
                        before, payload.afterQuantity(), payload.errorMessage());
    }

    private String stockMismatch(StockMismatchPayload payload) {
        return """
                🔍 재고 정합성 불일치

                [%s] %s
                재고 수량 : %d개
                이력 누적합: %d개
                차이     : %+d개

                재고 기준값과 변동 이력이 어긋났습니다. 이력을 확인해 원인을 찾아야 합니다."""
                .formatted(payload.skuCode(), payload.productName(),
                        payload.snapshotQuantity(), payload.historySum(), payload.difference());
    }

    private String dailyReport(DailyReportPayload payload) {
        StringBuilder builder = new StringBuilder()
                .append("📊 일일 리포트 (")
                .append(payload.date().format(DATE_ONLY))
                .append(")\n\n■ 판매 요약\n  총 주문   : ")
                .append(payload.totalOrderCount()).append("건\n  총 매출   : ")
                .append(money(payload.totalAmount())).append('\n');

        if (!payload.channels().isEmpty()) {
            builder.append("\n■ 채널별\n");
            for (DailyReportPayload.ChannelSummary channel : payload.channels()) {
                builder.append("  ").append(channel.channelName())
                        .append(" : ").append(channel.orderCount()).append("건 / ")
                        .append(money(channel.amount())).append('\n');
            }
        }

        if (!payload.topSkus().isEmpty()) {
            builder.append("\n■ 판매 상위\n");
            int rank = 1;
            for (DailyReportPayload.TopSku topSku : payload.topSkus()) {
                builder.append("  ").append(rank++).append(". [")
                        .append(topSku.skuCode()).append("] ")
                        .append(topSku.productName())
                        .append(" — ").append(topSku.quantity()).append("개\n");
            }
        }

        builder.append("\n■ 확인 필요\n");
        if (payload.hasWarnings()) {
            if (payload.lowStockCount() > 0) {
                builder.append("  ⚠️ 재고 부족 ").append(payload.lowStockCount()).append("건\n");
            }
            if (payload.unmappedCount() > 0) {
                builder.append("  ⚠️ 미매핑 상품 ").append(payload.unmappedCount()).append("건\n");
            }
            if (!payload.failedChannels().isEmpty()) {
                builder.append("  ❌ 수집 실패 : ")
                        .append(String.join(", ", payload.failedChannels())).append('\n');
            }
        } else {
            builder.append("  없음\n");
        }

        return builder.toString().stripTrailing();
    }

    // ------------------------------------------------------------------

    private static String money(long amount) {
        return "%,d원".formatted(amount);
    }

    private static String timestamp(Instant instant) {
        return TIMESTAMP.format(instant.atZone(DISPLAY_ZONE));
    }
}
