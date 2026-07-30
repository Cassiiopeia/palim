package kr.suhsaechan.palim.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 발송 대기 알림 (설계서 7장).
 *
 * <p>기능 명세서 4.3이 규정한 <i>"알림 발송 대상은 PostgreSQL에 먼저 기록한 후 큐에 투입한다"</i>
 * 를 구현한다. 주문 저장과 이 행의 삽입이 <b>같은 트랜잭션</b>이므로, 커밋되면 알림이 반드시
 * 발송되고 롤백되면 둘 다 사라진다.
 *
 * <p>Outbox 없이 큐만 쓰면 주문은 커밋됐는데 큐 발행이 실패하는 순간 알림이 영구 소실된다.
 * 이 테이블이 있어 RabbitMQ 중단 후 재가동 시에도 유실 없이 발송된다(A-14).
 */
@Getter
@Entity
@Table(name = "notification_outbox")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationOutbox extends BaseTimeEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    /** 알림 내용을 구성할 데이터. JSON 문자열로 보관한다. */
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(length = 1000)
    private String lastError;

    private Instant sentAt;

    @Version
    private Long version;

    private NotificationOutbox(NotificationType type, String payload) {
        this.id = UuidV7.generate();
        this.type = type;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
    }

    public static NotificationOutbox enqueue(NotificationType type, String payload) {
        return new NotificationOutbox(type, payload);
    }

    public void markSent(Instant sentAt) {
        this.status = OutboxStatus.SENT;
        this.sentAt = sentAt;
        this.lastError = null;
    }

    /**
     * 발송 시도 실패를 기록한다.
     *
     * <p>재시도 한도에 도달하지 않았으면 {@code PENDING} 으로 남겨 다음 주기에 다시 시도한다.
     */
    public void markAttemptFailed(String error, int maxAttempts) {
        this.attemptCount++;
        this.lastError = error;
        if (attemptCount >= maxAttempts) {
            this.status = OutboxStatus.FAILED;
        }
    }

    public boolean isPending() {
        return status == OutboxStatus.PENDING;
    }
}
