package kr.suhsaechan.palim.web.monitor;

import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.incident.IncidentStatus;
import kr.suhsaechan.palim.incident.IncidentType;
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
 * 인시던트 화면 (#34).
 *
 * <p>알림은 "알리는 것", 인시던트는 "처리를 추적하는 것"이다. "그 오버셀 처리했던가?"를
 * 기억이 아니라 이 화면이 답한다. 기본 탭은 미확인 — 조치 대상부터 보인다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class IncidentController {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGE = 1_000;

    private final IncidentAdminService incidentAdminService;
    private final ErrorMessageResolver errorMessageResolver;

    @GetMapping("/monitor/incidents")
    public String list(@RequestParam(required = false) IncidentStatus status,
                       @RequestParam(required = false, defaultValue = "false") boolean all,
                       @RequestParam(required = false) IncidentType type,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        // 미지정 + all 아님 → 미확인 탭. "전체"는 all=true 로 구분한다.
        IncidentStatus effective = all ? null : (status != null ? status : IncidentStatus.OPEN);

        Page<IncidentView> incidents = incidentAdminService.find(
                effective, type, PageRequest.of(Math.clamp(page, 0, MAX_PAGE), PAGE_SIZE));

        model.addAttribute("title", "인시던트");
        model.addAttribute("incidents", incidents);
        model.addAttribute("statuses", IncidentStatus.values());
        model.addAttribute("types", IncidentType.values());
        model.addAttribute("selectedStatus", effective);
        model.addAttribute("selectedType", type);
        model.addAttribute("showAll", all);
        model.addAttribute("unresolvedCount", incidentAdminService.countUnresolved());
        return "monitor/incidents";
    }

    @PostMapping("/monitor/incidents/{incidentId}/acknowledge")
    public String acknowledge(@PathVariable UUID incidentId,
                              RedirectAttributes redirectAttributes) {
        try {
            incidentAdminService.acknowledge(incidentId);
            redirectAttributes.addFlashAttribute("flashSuccess", "확인 상태로 변경했습니다.");
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return "redirect:/monitor/incidents";
    }

    @PostMapping("/monitor/incidents/{incidentId}/resolve")
    public String resolve(@PathVariable UUID incidentId,
                          @RequestParam String memo,
                          RedirectAttributes redirectAttributes) {
        try {
            incidentAdminService.resolve(incidentId, memo);
            redirectAttributes.addFlashAttribute("flashSuccess", "해결 처리했습니다.");
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return "redirect:/monitor/incidents";
    }

    private void addError(RedirectAttributes redirectAttributes, BusinessException exception) {
        log.warn("인시던트 처리 실패 — {}", exception.getErrorCode().name());
        redirectAttributes.addFlashAttribute("flashError",
                errorMessageResolver.resolve(exception.getErrorCode(), exception.messageArgs()));
    }
}
