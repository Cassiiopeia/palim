package kr.suhsaechan.palim.notification.telegram;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.notification.NotificationChannel;
import kr.suhsaechan.palim.notification.NotificationOutbox;
import kr.suhsaechan.palim.notification.NotificationSetting;
import kr.suhsaechan.palim.notification.NotificationSettingService;
import kr.suhsaechan.palim.notification.NotificationType;
import kr.suhsaechan.palim.notification.OrderAlertMode;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.notification.OutboxStateWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 를 읽어 텔레그램으로 발송한다.
 *
 * <h2>큐를 쓰지 않는 이유</h2>
 *
 * <p>설계서 7장은 {@code Outbox → relay → RabbitMQ → 워커} 구조로 정했으나, 현 규모에서
 * RabbitMQ 는 <b>이득 없이 이중 상태 관리를 만든다.</b> 워커 프로세스 분리는 단일 배포라
 * 무의미하고, 부하 분산도 일 주문 수십 건에는 필요 없으며, 재시도·DLQ 는 Outbox 가 이미
 * {@code attemptCount} 로 관리한다.
 *
 * <p>결정적으로 재시도가 두 곳에 생긴다. 발행 후 Outbox 를 {@code PENDING} 으로 두면 같은
 * 알림을 반복 발행하고, {@code SENT} 로 바꾸면 발송 실패를 Outbox 가 알지 못한다.
 * <b>재시도의 단일 근거는 Outbox 다.</b>
 *
 * <p>인수조건 A-14(큐 중단 후 재가동 시 유실 없이 발송)는 여전히 충족된다. 그 보장의 실제
 * 근거가 Outbox 이기 때문이다.
 *
 * <h2>트랜잭션</h2>
 *
 * <p>이 클래스는 트랜잭션을 열지 않는다. 텔레그램 응답을 기다리는 동안 데이터베이스 커넥션을
 * 점유하면 안 되므로, 상태 기록은 {@link OutboxStateWriter} 에 위임한다.
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class NotificationRelay implements ApplicationRunner {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");

    private final OutboxService outboxService;
    private final OutboxStateWriter outboxStateWriter;
    private final NotificationSettingService notificationSettingService;
    private final TelegramClient telegramClient;
    private final TelegramMessageFactory telegramMessageFactory;
    private final TelegramProperties telegramProperties;

    /**
     * 기동 직후 1회 실행.
     *
     * <p>재기동 전에 남은 알림을 이어서 발송한다. 이것이 A-14 를 충족시키는 지점이다.
     */
    @Override
    public void run(ApplicationArguments args) {
        long pendingCount = outboxService.countPending();
        if (pendingCount > 0) {
            log.info("기동 시 발송 대기 알림 {}건을 처리합니다.", pendingCount);
            relay();
        }
    }

    @Scheduled(fixedDelayString = "${palim.notification.relay-delay:30000}")
    public void relay() {
        // 자기 곳으로 갈 것만 가져간다. 한쪽이 준비되지 않았다고 다른 쪽까지 멈추면 안 된다.
        List<NotificationOutbox> pending =
                outboxService.findPending(NotificationChannel.TELEGRAM);
        if (pending.isEmpty()) {
            return;
        }

        NotificationSetting setting = notificationSettingService.get();
        if (!setting.isTelegramConnected()) {
            log.warn("텔레그램이 연결되지 않아 알림 {}건이 대기 중입니다. 웹 관리자에서 연결하세요.",
                    pending.size());
            return;
        }
        if (!telegramProperties.isConfigured()) {
            log.warn("텔레그램 봇 토큰이 설정되지 않아 알림 {}건이 대기 중입니다.", pending.size());
            return;
        }

        String chatId = setting.getTelegramChatId();
        boolean withinQuietHours = setting.isWithinQuietHours(LocalTime.now(DISPLAY_ZONE));

        List<NotificationOutbox> batchable = pending.stream()
                .filter(outbox -> outbox.getType().isBatchable())
                .toList();
        List<NotificationOutbox> individual = pending.stream()
                .filter(outbox -> !outbox.getType().isBatchable())
                .toList();

        sendIndividually(individual, chatId, withinQuietHours, setting);
        sendBatchableOrders(batchable, chatId, withinQuietHours, setting);
    }

    // ------------------------------------------------------------------

    private void sendIndividually(List<NotificationOutbox> outboxes, String chatId,
                                  boolean withinQuietHours, NotificationSetting setting) {
        for (NotificationOutbox outbox : outboxes) {
            if (shouldHold(outbox.getType(), withinQuietHours)) {
                continue;
            }
            send(outbox.getId(), telegramMessageFactory.create(outbox));
        }
    }

    /**
     * 주문 알림 발송 (F-02).
     *
     * <p>즉시 발송 모드면 건별로, 묶음 발송 모드면 설정 주기가 지난 뒤 하나로 합쳐 보낸다.
     * 주기가 지나지 않았으면 <b>보류한다</b> — 그 사이 들어오는 주문을 함께 묶기 위함이다.
     */
    private void sendBatchableOrders(List<NotificationOutbox> orders, String chatId,
                                     boolean withinQuietHours, NotificationSetting setting) {
        if (orders.isEmpty() || withinQuietHours) {
            return;
        }

        if (setting.getOrderAlertMode() == OrderAlertMode.IMMEDIATE) {
            for (NotificationOutbox outbox : orders) {
                send(outbox.getId(), telegramMessageFactory.create(outbox));
            }
            return;
        }

        Duration batchInterval = Duration.ofMinutes(setting.getBatchIntervalMinutes());
        Instant oldest = orders.stream()
                .map(NotificationOutbox::getCreatedAt)
                .min(Instant::compareTo)
                .orElseThrow();

        if (Instant.now().isBefore(oldest.plus(batchInterval))) {
            log.debug("묶음 발송 대기 — 주문 알림 {}건, 주기 {}분", orders.size(),
                    setting.getBatchIntervalMinutes());
            return;
        }

        String message = telegramMessageFactory.createBatched(orders);
        TelegramSendResult result = telegramClient.sendMessage(chatId, message);

        // 묶음은 전부 성공하거나 전부 재시도한다. 부분 성공을 표현할 방법이 없다.
        for (NotificationOutbox outbox : orders) {
            applyResult(outbox.getId(), result);
        }
    }

    /**
     * 야간 보류 판정 (F-02).
     *
     * <p>긴급 알림은 보류하지 않는다. 재고가 음수가 되었거나 수집이 멈춘 상황은 아침까지
     * 기다릴 수 없다.
     */
    private boolean shouldHold(NotificationType type, boolean withinQuietHours) {
        return withinQuietHours && !type.isUrgent();
    }

    private void send(UUID outboxId, String message) {
        applyResult(outboxId, telegramClient.sendMessage(
                notificationSettingService.get().getTelegramChatId(), message));
    }

    private void applyResult(UUID outboxId, TelegramSendResult result) {
        if (result.success()) {
            outboxStateWriter.markSent(outboxId);
            return;
        }
        if (result.retryable()) {
            outboxStateWriter.markAttemptFailed(outboxId, result.errorMessage(),
                    telegramProperties.maxAttempts());
            return;
        }
        log.error("알림 발송 영구 실패 — {} : {}", outboxId, result.errorMessage());
        outboxStateWriter.markPermanentlyFailed(outboxId, result.errorMessage());
    }
}
