package kr.suhsaechan.palim.web.settings;

import java.util.List;
import kr.suhsaechan.palim.notification.delivery.DeliverySetting;
import kr.suhsaechan.palim.notification.delivery.MailScope;

/**
 * 발송 관리 화면이 쓰는 것 전부.
 *
 * <p><b>비밀번호는 담기지 않는다.</b> 등록됐는지만 안다 — 확인용으로 한 번만 보여주는 자리가
 * 있으면 그 자리가 곧 유출 경로가 된다.
 *
 * @param passwordRegistered 비밀번호가 들어 있는가
 * @param mailReady          지금 메일을 보낼 수 있는가(서버 정보 + 비밀번호 + 받는 사람)
 * @param pendingCount       보내지 못하고 기다리는 건수
 * @param failedCount        보내다 실패한 건수
 */
public record DeliveryView(
        String smtpHost,
        int smtpPort,
        String smtpUsername,
        String fromAddress,
        boolean useStartTls,
        String recipients,
        MailScope mailScope,
        int digestHour,
        int digestMinute,
        boolean passwordRegistered,
        boolean mailReady,
        long pendingCount,
        long failedCount
) {

    public static DeliveryView of(DeliverySetting setting, boolean passwordRegistered,
                                  boolean mailReady, long pendingCount, long failedCount) {
        return new DeliveryView(
                setting.getSmtpHost(), setting.getSmtpPort(), setting.getSmtpUsername(),
                setting.getFromAddress(), setting.isUseStartTls(), setting.getRecipients(),
                setting.getMailScope(), setting.getDigestHour(), setting.getDigestMinute(),
                passwordRegistered, mailReady, pendingCount, failedCount);
    }

    /** 고를 수 있는 범위 전부. 화면이 값을 직접 늘어놓지 않게 한다. */
    public List<MailScope> scopes() {
        return List.of(MailScope.values());
    }

    /** 「매일 07:30 에 한 통 보냅니다」 처럼 저장된 값으로 문장을 만든다. */
    public String digestTimeText() {
        return "%02d:%02d".formatted(digestHour, digestMinute);
    }

    /** 아직 아무것도 넣지 않았는가. 빈 상태를 다르게 말하기 위해서다. */
    public boolean isBlank() {
        return smtpHost == null || smtpHost.isBlank();
    }
}
