package kr.suhsaechan.palim.notification;

import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 발송 상태 기록.
 *
 * <p>도메인 서비스의 변경 메서드가 {@code MANDATORY} 이므로 트랜잭션을 여는 계층이 필요하다.
 * 발송 relay 는 <b>외부 API 호출을 포함하므로 트랜잭션을 열지 않는다</b> — 텔레그램 응답을
 * 기다리는 동안 데이터베이스 커넥션을 점유하면 안 되기 때문이다. 상태 기록만 이 서비스에 위임한다.
 *
 * <p>{@code CollectStateService} 와 같은 패턴이다.
 */
@Service
@RequiredArgsConstructor
public class OutboxStateWriter {

    private final NotificationOutboxRepository notificationOutboxRepository;

    @Transactional
    public void markSent(UUID outboxId) {
        get(outboxId).markSent(Instant.now());
    }

    /** 일시적 실패. 재시도 한도를 넘으면 FAILED 로 전이된다. */
    @Transactional
    public void markAttemptFailed(UUID outboxId, String error, int maxAttempts) {
        get(outboxId).markAttemptFailed(error, maxAttempts);
    }

    /** 재시도해도 성공하지 않는 실패. 즉시 포기한다. */
    @Transactional
    public void markPermanentlyFailed(UUID outboxId, String error) {
        get(outboxId).markPermanentlyFailed(error);
    }

    private NotificationOutbox get(UUID outboxId) {
        return notificationOutboxRepository.findById(outboxId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND, outboxId));
    }
}
