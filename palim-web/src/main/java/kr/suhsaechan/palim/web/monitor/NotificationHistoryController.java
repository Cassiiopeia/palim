package kr.suhsaechan.palim.web.monitor;

import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.notification.OutboxStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 알림 발송 이력 화면 (#32).
 *
 * <p>"텔레그램이 안 왔다"가 발생했을 때 시스템이 안 보낸 건지 텔레그램이 죽은 건지 구분할 수
 * 있어야 한다. FAILED 는 사람이 확인해야 하는 상태로 설계됐는데(설계서 7장) 확인할 화면이
 * 없었다.
 *
 * <p>기본 탭은 실패다 — 조치 대상부터 보인다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class NotificationHistoryController {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGE = 1_000;

    private final NotificationHistoryService notificationHistoryService;
    private final ErrorMessageResolver errorMessageResolver;

    @GetMapping("/monitor/notifications")
    public String list(@RequestParam(required = false) OutboxStatus status,
                       @RequestParam(required = false, defaultValue = "false") boolean all,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        // 상태 미지정 + all 아님 → 실패 탭. "전체" 는 all=true 로 구분한다 —
        // status 파라미터 하나로는 "미지정=기본값" 과 "전체 선택" 을 구분할 수 없다.
        OutboxStatus effective = all ? null : (status != null ? status : OutboxStatus.FAILED);

        Page<NotificationHistoryView> history = notificationHistoryService.findHistory(
                effective, PageRequest.of(Math.clamp(page, 0, MAX_PAGE), PAGE_SIZE));

        model.addAttribute("title", "알림 이력");
        model.addAttribute("history", history);
        model.addAttribute("statuses", OutboxStatus.values());
        model.addAttribute("selectedStatus", effective);
        model.addAttribute("showAll", all);
        return "monitor/notifications";
    }

    @PostMapping("/monitor/notifications/{outboxId}/retry")
    public String retry(@PathVariable UUID outboxId, RedirectAttributes redirectAttributes) {
        try {
            notificationHistoryService.retry(outboxId);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "재발송 대기로 되돌렸습니다. 다음 발송 주기(약 30초 내)에 전송됩니다.");
        } catch (BusinessException exception) {
            log.warn("알림 재발송 실패 — {}", exception.getErrorCode().name());
            redirectAttributes.addFlashAttribute("flashError",
                    errorMessageResolver.resolve(exception.getErrorCode(), exception.messageArgs()));
        }
        return "redirect:/monitor/notifications";
    }
}
