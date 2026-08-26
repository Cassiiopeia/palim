package kr.suhsaechan.palim.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
        String json = serialize(payload);
        List<NotificationOutbox> saved = channels().stream()
                .map(channel -> notificationOutboxRepository.save(
                        NotificationOutbox.enqueue(type, channel, json)))
                .toList();
        // 부르는 쪽은 「등록됐는가」 만 본다. 여러 곳으로 나뉘어도 그 답은 하나다.
        return saved.getFirst();
    }

    /**
     * 최근에 같은 알림을 보내지 않았을 때만 등록한다.
     *
     * <p>감시 배치는 주기적으로 같은 상태를 발견한다. 재고가 계속 부족하면 매 주기마다 알림을
     * 등록하게 되는데, 그러면 <b>발주자가 알림을 아예 보지 않게 되어</b> 이 시스템의 존재 이유가
     * 무너진다. F-05 가 재알림 주기를 설정 항목으로 둔 이유다.
     *
     * @param dedupeKey 억제 키. {@code {알림종류}:{대상식별자}} 형식
     * @param within    이 기간 안에 같은 키로 등록된 알림이 있으면 건너뛴다
     * @return 등록했으면 해당 Outbox, 억제되었으면 빈 값
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<NotificationOutbox> enqueueIfNotRecent(NotificationType type, String dedupeKey,
                                                           Duration within, Object payload) {
        if (dedupeKey == null || dedupeKey.isBlank()) {
            throw new IllegalArgumentException("억제 키가 없으면 enqueue 를 쓴다");
        }
        Instant threshold = Instant.now().minus(within);
        // 억제는 «사건» 단위로 한 번만 판정한다. 보낼 곳마다 따로 보면 같은 사건이 여러 번
        // 등록되고, 한 곳을 껐다 켜는 것만으로 억제가 풀린다.
        if (notificationOutboxRepository.existsByDedupeKeyAndCreatedAtAfter(dedupeKey, threshold)) {
            return Optional.empty();
        }
        String json = serialize(payload);
        List<NotificationOutbox> saved = channels().stream()
                .map(channel -> notificationOutboxRepository.save(
                        NotificationOutbox.enqueue(type, channel, json, dedupeKey)))
                .toList();
        return Optional.of(saved.getFirst());
    }

    /**
     * 지금 <b>어디로</b> 보내는가.
     *
     * <p>나누는 자리가 여기인 이유 — 부르는 쪽의 트랜잭션 안에서 나뉘므로 「커밋되면 반드시
     * 발송되고 롤백되면 둘 다 사라진다」 가 보낼 곳마다 그대로 성립한다. 그리고 부르는 쪽을
     * 한 줄도 건드리지 않는다(적재 호출자 일부는 동결 도메인이라 이것이 결정적이다).
     *
     * <p>지금은 한 곳뿐이다. 메일이 붙으면 설정을 보고 늘어난다.
     */
    private List<NotificationChannel> channels() {
        return List.of(NotificationChannel.TELEGRAM);
    }

    /**
     * 발송 대기 목록.
     *
     * <p>relay 가 주기적으로 호출한다. 애플리케이션 기동 직후에도 호출해 재기동 전에 남은
     * 알림을 이어서 발송한다(A-14).
     */
    @Transactional(readOnly = true)
    public List<NotificationOutbox> findPending(NotificationChannel channel) {
        return notificationOutboxRepository.findByChannelAndStatusOrderByCreatedAtAsc(
                channel, OutboxStatus.PENDING, Limit.of(DEFAULT_FETCH_SIZE));
    }

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

    /**
     * 이력 조회 (#32). 최신 등록순.
     *
     * @param status {@code null} 이면 전체 상태
     */
    @Transactional(readOnly = true)
    public Page<NotificationOutbox> findHistory(OutboxStatus status, Pageable pageable) {
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return status == null
                ? notificationOutboxRepository.findAll(sorted)
                : notificationOutboxRepository.findByStatus(status, sorted);
    }

    /**
     * 실패한 알림을 재발송 대기로 되돌린다 (#32).
     *
     * <p>발송 자체는 기존 relay 가 다음 주기에 처리한다 — 화면 요청 스레드에서 텔레그램 API 를
     * 직접 호출하는 새 발송 경로를 만들지 않는다. 경로가 둘이 되면 재시도 판단 기준이 갈라진다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retryManually(UUID outboxId) {
        get(outboxId).retryManually();
    }

    @Transactional(readOnly = true)
    public NotificationOutbox get(UUID outboxId) {
        return notificationOutboxRepository.findById(outboxId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND, outboxId));
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
            throw new BusinessException(
                    ErrorCode.PAYLOAD_DESERIALIZE_FAILED, e, outbox.getId());
        }
    }

    private String serialize(Object payload) {
        if (payload instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.PAYLOAD_SERIALIZE_FAILED, e);
        }
    }
}
