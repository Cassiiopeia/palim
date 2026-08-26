package kr.suhsaechan.palim.notification.delivery;

import kr.suhsaechan.palim.notification.secret.NotificationSecretService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 발송 설정 읽기·쓰기. 한 줄만 있으므로 「그 한 줄」 을 다룬다. */
@Service
@RequiredArgsConstructor
public class DeliverySettingService {

    private final DeliverySettingRepository repository;
    private final NotificationSecretService secrets;

    @Transactional(readOnly = true)
    public DeliverySetting get() {
        return repository.findAll().stream().findFirst()
                .orElseGet(DeliverySetting::createDefault);
    }

    @Transactional
    public DeliverySetting initializeIfAbsent() {
        return repository.findAll().stream().findFirst()
                .orElseGet(() -> repository.save(DeliverySetting.createDefault()));
    }

    /**
     * 메일을 실제로 보낼 수 있는가.
     *
     * <p>서버 정보와 <b>비밀번호가 둘 다</b> 있어야 한다. 하나만 있으면 화면은 「넣었다」 로
     * 보이는데 발송만 조용히 실패한다.
     */
    @Transactional(readOnly = true)
    public boolean canSendMail() {
        return get().isMailConfigured()
                && secrets.exists(NotificationSecretService.SMTP_PASSWORD);
    }

    @Transactional
    public void changeSmtp(String host, int port, String username, String from, boolean startTls,
                           String password) {
        DeliverySetting setting = initializeIfAbsent();
        setting.changeSmtp(host, port, username, from, startTls);
        // 비운 채 저장하면 «바꾸지 않겠다» 는 뜻이다. 그러지 않으면 다른 값 하나를 고치려고
        // 화면을 열 때마다 비밀번호를 다시 쳐야 한다.
        if (password != null && !password.isBlank()) {
            secrets.put(NotificationSecretService.SMTP_PASSWORD, password);
        }
    }

    @Transactional
    public void changeRecipients(String csv, MailScope scope) {
        DeliverySetting setting = initializeIfAbsent();
        setting.changeRecipients(csv);
        setting.changeMailScope(scope);
    }

    @Transactional
    public void changeDigestTime(int hour, int minute) {
        initializeIfAbsent().changeDigestTime(hour, minute);
    }

    /** 비밀번호가 등록돼 있는가. 값 자체는 화면으로 나가지 않는다. */
    @Transactional(readOnly = true)
    public boolean hasPassword() {
        return secrets.exists(NotificationSecretService.SMTP_PASSWORD);
    }
}
