package kr.suhsaechan.palim.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 알림 Outbox 서비스 (설계서 7장).
 *
 * <p>{@link #enqueue}는 호출자의 트랜잭션에 참여한다. 이것이 이 설계의 핵심이다 —
 * 주문 저장과 알림 등록이 같은 트랜잭션이므로, 커밋되면 알림이 반드시 발송되고 롤백되면
 * 둘 다 사라진다. 큐에 직접 발행하면 주문은 커밋됐는데 발행이 실패하는 순간 알림이 영구
 * 소실된다(A-14).
 *
 * <p>발송 상태 전이는 relay 와 워커가 사용한다.
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    /** 재시도 한도. 초과하면 FAILED 로 두고 사람이 확인해야 한다. */
    private static final int MAX_ATTEMPTS = 5;

    private static final int DEFAULT_FETCH_SIZE = 100;

    private final NotificationOutboxRepository notificationOutboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * 알림을 등록한다. 호출자의 트랜잭션에 참여한다.
     *
     * @param payload 알림 내용을 구성할 데이터. record 를 넘기면 JSON 으로 직렬화된다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public NotificationOutbox enqueue(NotificationType type, Object payload) {
        return notificationOutboxRepository.save(
                NotificationOutbox.enqueue(type, serialize(payload)));
    }

    /**
     * 발송 대기 목록.
     *
     * <p>relay 가 주기적으로 호출한다. 애플리케이션 기동 직후에도 호출해 재기동 전에 남은
     * 알림을 이어서 발송한다(A-14).
     */
    @Transactional(readOnly = true)
    public List<NotificationOutbox> findPending() {
        return notificationOutboxRepository.findByStatusOrderByCreatedAtAsc(
                OutboxStatus.PENDING, Limit.of(DEFAULT_FETCH_SIZE));
    }

    @Transactional(readOnly = true)
    public List<NotificationOutbox> findPending(NotificationType type) {
        return notificationOutboxRepository.findByStatusAndTypeOrderByCreatedAtAsc(
                OutboxStatus.PENDING, type);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markSent(UUID outboxId) {
        get(outboxId).markSent(Instant.now());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markAttemptFailed(UUID outboxId, String error) {
        get(outboxId).markAttemptFailed(error, MAX_ATTEMPTS);
    }

    /**
     * 적체 건수.
     *
     * <p>임계치를 넘으면 텔레그램으로 경고한다. 별도 관측 스택을 두지 않고 시스템 자기 감시를
     * 같은 알림 경로로 처리하기 때문이다(설계서 9.3).
     */
    @Transactional(readOnly = true)
    public long countPending() {
        return notificationOutboxRepository.countByStatus(OutboxStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public long countFailed() {
        return notificationOutboxRepository.countByStatus(OutboxStatus.FAILED);
    }

    @Transactional(readOnly = true)
    public NotificationOutbox get(UUID outboxId) {
        return notificationOutboxRepository.findById(outboxId)
                .orElseThrow(() -> NotFoundException.of("알림", outboxId));
    }

    /**
     * payload 를 원하는 타입으로 역직렬화한다. 발송 워커가 메시지를 구성할 때 쓴다.
     *
     * <p>Spring Boot 4 는 Jackson 3(`tools.jackson`)을 쓴다. Jackson 3 에서는 모든 예외가
     * unchecked 이므로 {@code JacksonException} 을 명시적으로 잡아 문맥을 붙인다.
     */
    public <T> T readPayload(NotificationOutbox outbox, Class<T> type) {
        try {
            return objectMapper.readValue(outbox.getPayload(), type);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "알림 payload 를 해석할 수 없습니다: " + outbox.getId(), e);
        }
    }

    private String serialize(Object payload) {
        if (payload instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("알림 payload 직렬화에 실패했습니다", e);
        }
    }
}
