package kr.suhsaechan.palim.web.monitor;

import java.util.UUID;
import kr.suhsaechan.palim.audit.AuditType;
import kr.suhsaechan.palim.notification.NotificationOutbox;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.notification.OutboxStatus;
import kr.suhsaechan.palim.web.audit.WebAuditRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 발송 이력 화면용 조율 서비스 (#32).
 *
 * <p>{@code OutboxService.retryManually} 가 {@code MANDATORY} 이므로 트랜잭션을 여는 계층이
 * 필요하다. 재발송은 감사 대상이다 — 알림을 되살린 것도 운영 행위이며, 중복 발송 문의가 왔을 때
 * "누가 언제 되돌렸는지"가 답이 된다.
 */
@Service
@RequiredArgsConstructor
public class NotificationHistoryService {

    private final OutboxService outboxService;
    private final WebAuditRecorder webAuditRecorder;

    @Transactional(readOnly = true)
    public Page<NotificationHistoryView> findHistory(OutboxStatus status, Pageable pageable) {
        return outboxService.findHistory(status, pageable).map(NotificationHistoryView::from);
    }

    @Transactional
    public void retry(UUID outboxId) {
        NotificationOutbox outbox = outboxService.get(outboxId);
        outboxService.retryManually(outboxId);

        webAuditRecorder.recordChange(AuditType.NOTIFICATION_RESEND,
                "NOTIFICATION", outboxId.toString(),
                "%s 알림을 재발송 대기로 되돌렸습니다.".formatted(outbox.getType().displayName()),
                null, null);
    }
}
