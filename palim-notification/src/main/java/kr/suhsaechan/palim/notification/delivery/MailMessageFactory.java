package kr.suhsaechan.palim.notification.delivery;

import kr.suhsaechan.palim.notification.NotificationOutbox;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.notification.payload.ReconcileDigestPayload;
import kr.suhsaechan.palim.notification.telegram.TelegramMessageFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 알림 한 건을 <b>메일 한 통</b>으로.
 *
 * <p>본문은 메신저용 문구를 그대로 쓴다. 두 곳이 다른 문장을 말하면 「메일에는 있는데 메신저에는
 * 없다」 가 생기고, 그때 어느 쪽이 맞는지 알 방법이 없다.
 *
 * <p>제목만 따로 만든다. 요약 알림은 <b>제목이 상태를 말해야</b> 하고, 그 문자열은 메신저 첫
 * 줄과 같은 것을 쓴다.
 */
@Component
@RequiredArgsConstructor
public class MailMessageFactory {

    private final OutboxService outboxService;
    private final TelegramMessageFactory bodyFactory;

    public MailMessage create(NotificationOutbox outbox) {
        return new MailMessage(subject(outbox), bodyFactory.create(outbox));
    }

    private String subject(NotificationOutbox outbox) {
        if (outbox.getType() == kr.suhsaechan.palim.notification.NotificationType.RECONCILE_DIGEST) {
            // 요약은 제목이 곧 판단 근거다. 내용에서 계산한 것을 그대로 쓴다.
            return outboxService.readPayload(outbox, ReconcileDigestPayload.class).subject();
        }
        String prefix = outbox.getType().isUrgent() ? "[급함] " : "[알림] ";
        return prefix + outbox.getType().displayName();
    }
}
