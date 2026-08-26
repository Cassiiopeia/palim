package kr.suhsaechan.palim.notification.delivery;

import java.util.List;
import kr.suhsaechan.palim.notification.NotificationChannel;
import kr.suhsaechan.palim.notification.NotificationOutbox;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.notification.OutboxStateWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 대기 중인 메일을 보낸다.
 *
 * <p>메신저 중계와 <b>나란히</b> 돈다. 하나로 합치지 않는 이유는 한쪽이 준비되지 않았거나
 * 막혔을 때 <b>다른 쪽까지 멈추면 안 되기</b> 때문이다. 실제로 지금까지는 메신저가 연결되지
 * 않으면 모든 알림이 함께 대기했다.
 *
 * <p>준비되지 않았으면 <b>보내지 않고 남겨 둔다.</b> 실패로 두면 시도 횟수가 쌓여 나중에 설정을
 * 넣어도 이미 포기한 상태가 된다. 그리고 이것이 <b>시험이 실제 메일 서버에 접속하지 않는
 * 실질적인 장치</b>다 — 시험 환경에는 서버 정보가 없으므로 연결을 아예 열지 않는다.
 *
 * <p>트랜잭션을 열지 않는다. 메일 서버 응답을 기다리는 동안 DB 연결을 붙들고 있으면 안 되므로
 * 상태 기록은 따로 맡긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailRelay {

    private final OutboxService outboxService;
    private final OutboxStateWriter outboxStateWriter;
    private final DeliverySettingService settings;
    private final SmtpMailSender sender;
    private final MailMessageFactory messageFactory;

    @Value("${palim.notification.max-attempts:5}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${palim.notification.relay-delay:30000}")
    public void relay() {
        List<NotificationOutbox> pending = outboxService.findPending(NotificationChannel.EMAIL);
        if (pending.isEmpty()) {
            return;
        }
        if (!sender.isConfigured()) {
            log.debug("메일 서버가 등록되지 않아 {}건이 대기 중입니다.", pending.size());
            return;
        }

        MailScope scope = settings.get().getMailScope();
        for (NotificationOutbox outbox : pending) {
            if (!scope.includes(outbox.getType())) {
                // 메일로 받지 않기로 한 종류다. 대기로 남기면 영영 쌓이므로 «보낸 것으로»
                // 정리한다 — 메신저로는 이미 갔다.
                outboxStateWriter.markSent(outbox.getId());
                continue;
            }
            send(outbox);
        }
    }

    private void send(NotificationOutbox outbox) {
        MailMessage message = messageFactory.create(outbox);
        MailSendResult result = sender.send(message.subject(), message.body());

        if (result.success()) {
            outboxStateWriter.markSent(outbox.getId());
            return;
        }
        if (result.retryable()) {
            outboxStateWriter.markAttemptFailed(outbox.getId(), result.errorMessage(), maxAttempts);
            return;
        }
        // 다시 해도 안 되는 것은 곧바로 접는다. 계속 시도하면 계정이 잠긴다.
        outboxStateWriter.markPermanentlyFailed(outbox.getId(), result.errorMessage());
    }
}
