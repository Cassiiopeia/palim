package kr.suhsaechan.palim.web.settings;

import java.time.LocalTime;
import kr.suhsaechan.palim.audit.AuditType;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.notification.NotificationSettingService;
import kr.suhsaechan.palim.notification.OrderAlertMode;
import kr.suhsaechan.palim.notification.OutboxService;
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
 * 알림 설정 화면 (F-02, F-05, F-06).
 *
 * <p><b>텔레그램을 연결하지 않으면 알림이 발송되지 않는다.</b> Outbox 에 쌓이기만 하므로 유실은
 * 없지만, 발주자는 아무것도 받지 못한다. 도입 시 채널 설정 다음으로 필요한 화면이다.
 *
 * <p>변경은 재시작 없이 즉시 반영된다 — 발송 시점마다 설정을 조회하기 때문이다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class NotificationSettingController {

    private static final String REDIRECT = "redirect:/settings/notification";

    private final NotificationSettingService notificationSettingService;
    private final OutboxService outboxService;
    private final ErrorMessageResolver errorMessageResolver;
    private final WebAuditRecorder webAuditRecorder;

    @GetMapping("/settings/notification")
    public String view(Model model) {
        model.addAttribute("title", "알림 설정");
        model.addAttribute("setting", notificationSettingService.get());
        model.addAttribute("alertModes", OrderAlertMode.values());
        // 연결 전이라면 여기에 쌓인 수치가 곧 "받지 못한 알림"의 양이다.
        model.addAttribute("pendingCount", outboxService.countPending());
        model.addAttribute("failedCount", outboxService.countFailed());
        return "settings/notification";
    }

    @PostMapping("/settings/notification/telegram")
    public String connectTelegram(@RequestParam String telegramChatId,
                                  RedirectAttributes redirectAttributes) {
        try {
            notificationSettingService.connectTelegram(telegramChatId.trim());
            // chat_id 는 스냅샷에 넣지 않는다 — 알면 봇 메시지를 가로챌 단서가 된다.
            webAuditRecorder.recordChange(AuditType.NOTIFICATION_SETTING_UPDATE,
                    "텔레그램 연결을 변경했습니다.", null, null);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "텔레그램을 연결했습니다. 대기 중인 알림이 곧 발송됩니다.");
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return REDIRECT;
    }

    @PostMapping("/settings/notification/alert-mode")
    public String changeAlertMode(@RequestParam OrderAlertMode mode,
                                 @RequestParam int batchIntervalMinutes,
                                 RedirectAttributes redirectAttributes) {
        try {
            notificationSettingService.changeOrderAlertMode(mode, batchIntervalMinutes);
            webAuditRecorder.recordChange(AuditType.NOTIFICATION_SETTING_UPDATE,
                    "알림 발송 방식을 %s(으)로 변경했습니다.".formatted(mode), null, null);
            redirectAttributes.addFlashAttribute("flashSuccess", "발송 방식을 변경했습니다.");
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return REDIRECT;
    }

    /**
     * 야간 발송 보류 시간대.
     *
     * <p>빈 값을 보내면 사용하지 않는 것으로 처리한다. 긴급 알림(오버셀링·품절·수집 실패)은
     * 이 설정과 무관하게 즉시 발송된다 — 아침까지 기다릴 수 없는 사안이다.
     */
    @PostMapping("/settings/notification/quiet-hours")
    public String changeQuietHours(@RequestParam(required = false) String start,
                                   @RequestParam(required = false) String end,
                                   RedirectAttributes redirectAttributes) {
        try {
            LocalTime parsedStart = parseTime(start);
            LocalTime parsedEnd = parseTime(end);
            notificationSettingService.changeQuietHours(parsedStart, parsedEnd);
            webAuditRecorder.recordChange(AuditType.NOTIFICATION_SETTING_UPDATE,
                    parsedStart != null
                            ? "야간 발송 보류를 %s~%s 로 설정했습니다.".formatted(parsedStart, parsedEnd)
                            : "야간 발송 보류를 해제했습니다.", null, null);

            redirectAttributes.addFlashAttribute("flashSuccess", parsedStart != null
                    ? "야간 발송 보류를 설정했습니다."
                    : "야간 발송 보류를 해제했습니다.");
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return REDIRECT;
    }

    @PostMapping("/settings/notification/daily-report")
    public String changeDailyReport(@RequestParam(defaultValue = "false") boolean enabled,
                                   @RequestParam(required = false) String time,
                                   RedirectAttributes redirectAttributes) {
        try {
            notificationSettingService.changeDailyReport(enabled, parseTime(time));
            webAuditRecorder.recordChange(AuditType.NOTIFICATION_SETTING_UPDATE,
                    "일일 리포트를 %s했습니다.".formatted(enabled ? "활성화" : "비활성화"), null, null);
            redirectAttributes.addFlashAttribute("flashSuccess", "일일 리포트 설정을 변경했습니다.");
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return REDIRECT;
    }

    @PostMapping("/settings/notification/low-stock-repeat")
    public String changeLowStockRepeat(@RequestParam int hours,
                                       RedirectAttributes redirectAttributes) {
        try {
            notificationSettingService.changeLowStockRepeatHours(hours);
            webAuditRecorder.recordChange(AuditType.NOTIFICATION_SETTING_UPDATE,
                    "재고 부족 재알림 주기를 %d시간으로 변경했습니다.".formatted(hours), null, null);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "재고 부족 재알림 주기를 %d시간으로 변경했습니다.".formatted(hours));
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return REDIRECT;
    }

    private static LocalTime parseTime(String value) {
        return value == null || value.isBlank() ? null : LocalTime.parse(value);
    }

    private void addError(RedirectAttributes redirectAttributes, BusinessException exception) {
        log.warn("알림 설정 변경 실패 — {}", exception.getErrorCode().name());
        redirectAttributes.addFlashAttribute("flashError",
                errorMessageResolver.resolve(exception.getErrorCode(), exception.messageArgs()));
    }
}
