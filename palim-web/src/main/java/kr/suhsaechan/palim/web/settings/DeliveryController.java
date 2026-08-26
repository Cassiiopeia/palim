package kr.suhsaechan.palim.web.settings;

import kr.suhsaechan.palim.audit.AuditType;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.notification.delivery.DeliverySettingService;
import kr.suhsaechan.palim.notification.delivery.MailScope;
import kr.suhsaechan.palim.notification.delivery.MailSendResult;
import kr.suhsaechan.palim.notification.delivery.SmtpMailSender;
import kr.suhsaechan.palim.web.audit.WebAuditRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 발송 관리 — <b>어디로, 무엇을, 언제</b> 보낼지 한 자리에서 정한다.
 *
 * <p>대조 시각과 발송 시각을 같은 화면에 두는 데는 이유가 있다. <b>둘은 순서가 있어야 한다</b> —
 * 대조가 먼저, 발송이 나중이다. 두 화면에 흩어 두면 그 순서가 깨져도 아무도 못 본다. 발송이
 * 먼저면 매일 <b>어제 결과</b>를 보내게 되는데, 그것은 틀린 값이 아니라 <b>하루 낡은 값</b>이라
 * 이상하다고 느끼지 못한다.
 *
 * <p>폼 화면이므로 실패를 예외로 던지지 않는다 — 안내로 되돌려 준다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class DeliveryController {

    private static final String REDIRECT = "redirect:/settings/delivery";

    private final DeliverySettingService deliverySettingService;
    private final SmtpMailSender mailSender;
    private final OutboxService outboxService;
    private final WebAuditRecorder webAuditRecorder;
    private final ErrorMessageResolver errorMessageResolver;

    @GetMapping("/settings/delivery")
    public String view(Model model) {
        model.addAttribute("title", "발송 관리");
        model.addAttribute("delivery", DeliveryView.of(
                deliverySettingService.get(),
                deliverySettingService.hasPassword(),
                deliverySettingService.canSendMail(),
                outboxService.countPending(),
                outboxService.countFailed()));
        return "settings/delivery";
    }

    @PostMapping("/settings/delivery/smtp")
    public String changeSmtp(@RequestParam(required = false) String smtpHost,
                             @RequestParam(defaultValue = "587") int smtpPort,
                             @RequestParam(required = false) String smtpUsername,
                             @RequestParam(required = false) String fromAddress,
                             @RequestParam(defaultValue = "false") boolean useStartTls,
                             @RequestParam(required = false) String smtpPassword,
                             RedirectAttributes redirect) {
        try {
            deliverySettingService.changeSmtp(smtpHost, smtpPort, smtpUsername, fromAddress,
                    useStartTls, smtpPassword);
            // 비밀번호도 계정도 남기지 않는다. 기록은 「바꿨다」 까지만 말한다.
            webAuditRecorder.recordChange(AuditType.NOTIFICATION_SETTING_UPDATE,
                    "메일 서버 설정을 변경했습니다.", null, null);
            redirect.addFlashAttribute("flashSuccess",
                    "메일 서버를 저장했습니다. 「테스트 메일 보내기」로 확인해 보세요.");
        } catch (BusinessException e) {
            addError(redirect, e);
        }
        return REDIRECT;
    }

    @PostMapping("/settings/delivery/recipients")
    public String changeRecipients(@RequestParam(required = false) String recipients,
                                   @RequestParam MailScope mailScope,
                                   RedirectAttributes redirect) {
        try {
            deliverySettingService.changeRecipients(recipients, mailScope);
            // 받는 주소는 기록에 넣지 않는다 — 개인정보다.
            webAuditRecorder.recordChange(AuditType.NOTIFICATION_SETTING_UPDATE,
                    "메일 받는 사람과 범위를 변경했습니다.", null, null);
            redirect.addFlashAttribute("flashSuccess", "받는 사람을 저장했습니다.");
        } catch (BusinessException e) {
            addError(redirect, e);
        }
        return REDIRECT;
    }

    @PostMapping("/settings/delivery/schedule")
    public String changeSchedule(@RequestParam int digestHour,
                                 @RequestParam int digestMinute,
                                 RedirectAttributes redirect) {
        try {
            deliverySettingService.changeDigestTime(digestHour, digestMinute);
            webAuditRecorder.recordChange(AuditType.NOTIFICATION_SETTING_UPDATE,
                    "요약 발송 시각을 %02d:%02d 로 변경했습니다.".formatted(digestHour, digestMinute),
                    null, null);
            redirect.addFlashAttribute("flashSuccess",
                    "매일 %02d:%02d 에 한 통 보냅니다.".formatted(digestHour, digestMinute));
        } catch (BusinessException e) {
            addError(redirect, e);
        }
        return REDIRECT;
    }

    /**
     * 테스트 메일.
     *
     * <p>발송 큐를 거치지 않고 곧바로 보낸다 — 이것은 알림이 아니라 <b>연결 확인</b>이라
     * 재시도 대상이 아니고, 큐에 남으면 「보내지 못한 알림」 으로 세어진다.
     */
    @PostMapping("/settings/delivery/test-mail")
    public String sendTestMail(RedirectAttributes redirect) {
        if (!deliverySettingService.canSendMail()) {
            redirect.addFlashAttribute("flashError",
                    "메일 서버·비밀번호·받는 사람을 먼저 채워 주세요.");
            return REDIRECT;
        }
        MailSendResult result = mailSender.send("[대조] 테스트 메일",
                "이 메일이 보이면 발송 설정이 정상입니다.\n\n"
                        + "재고 대조 요약이 매일 이 주소로 옵니다.");
        if (result.success()) {
            redirect.addFlashAttribute("flashSuccess", "테스트 메일을 보냈습니다. 받은 편지함을 확인해 주세요.");
        } else {
            // 사유를 그대로 보여준다. 「실패했습니다」 만으로는 무엇을 고쳐야 할지 알 수 없다.
            redirect.addFlashAttribute("flashError",
                    "보내지 못했습니다 — %s".formatted(result.errorMessage()));
        }
        return REDIRECT;
    }

    private void addError(RedirectAttributes redirect, BusinessException exception) {
        redirect.addFlashAttribute("flashError",
                errorMessageResolver.resolve(exception.getErrorCode(), exception.messageArgs()));
    }
}
