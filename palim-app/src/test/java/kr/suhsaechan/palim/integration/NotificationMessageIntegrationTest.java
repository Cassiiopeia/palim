package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.notification.NotificationOutbox;
import kr.suhsaechan.palim.notification.NotificationType;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.notification.payload.CollectFailurePayload;
import kr.suhsaechan.palim.notification.payload.DailyReportPayload;
import kr.suhsaechan.palim.notification.payload.LowStockPayload;
import kr.suhsaechan.palim.notification.payload.NewOrderPayload;
import kr.suhsaechan.palim.notification.payload.OutOfStockPayload;
import kr.suhsaechan.palim.notification.payload.OverSellPayload;
import kr.suhsaechan.palim.notification.payload.RisingInfluencerPayload;
import kr.suhsaechan.palim.notification.payload.ReconcileBlockedPayload;
import kr.suhsaechan.palim.notification.payload.ReconcileDigestPayload;
import kr.suhsaechan.palim.notification.payload.ReconcileMismatchPayload;
import kr.suhsaechan.palim.notification.payload.StockMismatchPayload;
import kr.suhsaechan.palim.notification.payload.StockPushFailurePayload;
import kr.suhsaechan.palim.notification.payload.UnmappedProductPayload;
import kr.suhsaechan.palim.notification.telegram.TelegramMessageFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 메시지 조립 검증.
 *
 * <p>알림 종류를 추가하고 메시지 조립을 빠뜨리면 발송 시점에야 터진다. 그때는 이미 Outbox 에
 * 쌓인 뒤이므로, <b>전 종류가 조립되는지를 빌드에서 확인</b>한다.
 */
@Transactional
class NotificationMessageIntegrationTest extends IntegrationTest {

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private TelegramMessageFactory telegramMessageFactory;

    /** 알림 종류별 대표 payload. 새 종류를 추가하면 여기에도 추가해야 테스트가 통과한다. */
    private static Object samplePayloadOf(NotificationType type) {
        return switch (type) {
            case NEW_ORDER -> new NewOrderPayload("쿠팡", "ORD-1", "SKU-001", "테스트 상품",
                    2, 39_800L, Instant.parse("2026-07-28T05:32:00Z"), 18);
            case UNMAPPED_PRODUCT -> new UnmappedProductPayload("네이버", "ORD-2",
                    "PRODUCT-9", "OPT-1", "미매핑 상품", 1);
            case OVERSELL -> new OverSellPayload("쿠팡", "ORD-3", "SKU-002", "초과판매 상품", 5, -2);
            case LOW_STOCK -> new LowStockPayload("SKU-003", "재고부족 상품", 3, 5, 2.1);
            case OUT_OF_STOCK -> new OutOfStockPayload("SKU-004", "품절 상품");
            case COLLECT_FAILURE -> new CollectFailurePayload("11번가", 3, true,
                    "401 Unauthorized", Instant.parse("2026-07-30T01:00:00Z"));
            case STOCK_PUSH_FAILURE -> new StockPushFailurePayload("롯데온", "SKU-005",
                    "전송실패 상품", 10, 0, true, "변동량 상한 초과");
            case STOCK_MISMATCH -> new StockMismatchPayload("SKU-006", "불일치 상품", 20, 17);
            // 대조 알림 둘은 위 STOCK_MISMATCH 와 «다른 사건» 이다. 한때 종류를 빌려 써서
            // 받는 쪽이 빈 값을 그렸다 — 여기 각자 들어 있는 것 자체가 그 재발을 막는다.
            case RECONCILE_MISMATCH -> new ReconcileMismatchPayload(
                    "전산 대 물류", "erp-stock", "wms-stock",
                    Instant.parse("2026-08-15T00:00:00Z"), 7,
                    List.of(new ReconcileMismatchPayload.Sample("UNIT-1",
                            new BigDecimal("120"), new BigDecimal("118"), new BigDecimal("2"))));
            case RECONCILE_BLOCKED -> new ReconcileBlockedPayload(
                    "전산 대 물류", "「물류」 쪽에 비교할 재고가 없습니다.", 3,
                    Instant.parse("2026-08-15T06:30:00Z"));
            case DAILY_REPORT -> new DailyReportPayload(
                    LocalDate.of(2026, 7, 27), 24, 487_600L,
                    List.of(new DailyReportPayload.ChannelSummary("쿠팡", 14, 281_200L),
                            new DailyReportPayload.ChannelSummary("네이버", 8, 158_400L)),
                    List.of(new DailyReportPayload.TopSku("SKU-003", "상위 상품", 9)),
                    3, 1, List.of());
            case RISING_INFLUENCER -> new RisingInfluencerPayload(2, 7,
                    List.of(new RisingInfluencerPayload.RisingChannel(
                            "합성 캠핑 채널", 42_000, 180_000, 87.0, 3.2, 3)),
                    Instant.parse("2026-08-04T00:00:00Z"));
            // 요약은 «이상이 없어도» 나가는 유일한 알림이다. 그래서 0 건짜리 표본을 쓴다 —
            // 건수가 있을 때만 문구가 만들어지면 정작 평온한 날에 빈 메일이 간다.
            case RECONCILE_DIGEST -> new ReconcileDigestPayload(
                    LocalDate.of(2026, 8, 25), 1, 1, 0, 0, 0, 0,
                    List.of(), List.of("전산 대 물류 · 차이 없음"));
        };
    }

    @Test
    @DisplayName("모든 알림 종류의 메시지가 조립된다")
    void 전_종류_메시지가_조립된다() {
        List<String> failures = new ArrayList<>();

        for (NotificationType type : NotificationType.values()) {
            NotificationOutbox outbox = outboxService.enqueue(type, samplePayloadOf(type));
            try {
                String message = telegramMessageFactory.create(outbox);
                if (message == null || message.isBlank()) {
                    failures.add(type.name() + " (빈 메시지)");
                }
            } catch (RuntimeException exception) {
                failures.add("%s (%s)".formatted(type.name(), exception.getMessage()));
            }
        }

        assertThat(failures).as("메시지 조립에 실패한 알림 종류").isEmpty();
    }

    @Test
    @DisplayName("주문 알림은 명세서 형식을 따른다")
    void 주문_알림_형식을_지킨다() {
        NotificationOutbox outbox = outboxService.enqueue(
                NotificationType.NEW_ORDER, samplePayloadOf(NotificationType.NEW_ORDER));

        String message = telegramMessageFactory.create(outbox);

        assertThat(message)
                .contains("🛒 신규 주문")
                .contains("채널   : 쿠팡")
                .contains("[SKU-001] 테스트 상품")
                .contains("2개")
                .contains("39,800원")
                .contains("현재 재고: 18개")
                // Instant 는 UTC 지만 표시는 KST 여야 한다. 05:32Z → 14:32 KST
                .contains("07-28 14:32");
    }

    @Test
    @DisplayName("여러 주문을 하나의 메시지로 묶는다")
    void 주문_알림을_묶는다() {
        List<NotificationOutbox> orders = List.of(
                outboxService.enqueue(NotificationType.NEW_ORDER,
                        new NewOrderPayload("쿠팡", "ORD-A", "SKU-001", "상품A",
                                2, 20_000L, Instant.now(), 8)),
                outboxService.enqueue(NotificationType.NEW_ORDER,
                        new NewOrderPayload("네이버", "ORD-B", "SKU-002", "상품B",
                                1, 15_000L, Instant.now(), 4)));

        String message = telegramMessageFactory.createBatched(orders);

        assertThat(message)
                .contains("신규 주문 2건")
                .contains("상품A")
                .contains("상품B")
                .contains("합계 : 3개 / 35,000원");
    }

    @Test
    @DisplayName("묶음 대상이 하나면 개별 형식으로 보낸다")
    void 하나면_개별_형식이다() {
        NotificationOutbox outbox = outboxService.enqueue(
                NotificationType.NEW_ORDER, samplePayloadOf(NotificationType.NEW_ORDER));

        assertThat(telegramMessageFactory.createBatched(List.of(outbox)))
                .isEqualTo(telegramMessageFactory.create(outbox));
    }

    /**
     * 재고가 음수가 되었거나 수집이 멈춘 상황은 아침까지 기다릴 수 없다. 발주자가 알림 과다를
     * 우려해 야간 보류를 켰더라도 매출 손실로 직결되는 사안은 즉시 알려야 한다.
     */
    @Test
    @DisplayName("긴급 알림은 야간 보류와 묶음에서 제외된다")
    void 긴급_알림_판별이_맞다() {
        Map<NotificationType, Boolean> expected = Map.of(
                NotificationType.NEW_ORDER, false,
                NotificationType.UNMAPPED_PRODUCT, false,
                NotificationType.LOW_STOCK, false,
                NotificationType.DAILY_REPORT, false,
                NotificationType.OVERSELL, true,
                NotificationType.OUT_OF_STOCK, true,
                NotificationType.COLLECT_FAILURE, true,
                NotificationType.STOCK_PUSH_FAILURE, true,
                NotificationType.STOCK_MISMATCH, true);

        expected.forEach((type, urgent) ->
                assertThat(type.isUrgent()).as("%s 의 긴급 여부", type).isEqualTo(urgent));
    }

    @Test
    @DisplayName("묶음 대상은 주문 알림뿐이다")
    void 묶음_대상은_주문뿐이다() {
        for (NotificationType type : NotificationType.values()) {
            assertThat(type.isBatchable())
                    .as("%s 의 묶음 대상 여부", type)
                    .isEqualTo(type == NotificationType.NEW_ORDER);
        }
    }
}
