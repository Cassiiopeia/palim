package kr.suhsaechan.palim.web.monitor;

import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.incident.IncidentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 인시던트 화면 (#35).
 *
 * <p>텔레그램 알림은 흘러가면 끝이다. 이 화면은 오버셀·정합성 불일치·미매핑을 사람이 마감할
 * 때까지 붙잡아 두고, "지금 열려 있는 문제가 몇 개인지"를 답한다.
 *
 * <p>기본 탭은 미확인이다 — 조치 대상부터 보인다.
 *
 * @deprecated 재고 시스템 동결(07-DECISIONS 023). 내비게이션에서 제거되었고 수정하지 않는다.
 */
@Deprecated
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
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        // 상태 미지정 + all 아님 → 미확인 탭. "전체" 는 all=true 로 구분한다 —
        // status 파라미터 하나로는 "미지정=기본값" 과 "전체 선택" 을 구분할 수 없다.
        IncidentStatus effective = all ? null : (status != null ? status : IncidentStatus.OPEN);

        Page<IncidentView> incidents = incidentAdminService.findIncidents(effective,
                PageRequest.of(Math.clamp(page, 0, MAX_PAGE), PAGE_SIZE,
                        Sort.by(Sort.Direction.DESC, "lastOccurredAt")));

        model.addAttribute("title", "인시던트");
        model.addAttribute("incidents", incidents);
        model.addAttribute("statuses", IncidentStatus.values());
        model.addAttribute("selectedStatus", effective);
        model.addAttribute("showAll", all);
        return "monitor/incidents";
    }

    @PostMapping("/monitor/incidents/{incidentId}/acknowledge")
    public String acknowledge(@PathVariable UUID incidentId,
                              RedirectAttributes redirectAttributes) {
        try {
            incidentAdminService.acknowledge(incidentId);
            redirectAttributes.addFlashAttribute("flashSuccess", "확인 상태로 변경했습니다.");
        } catch (BusinessException exception) {
            log.warn("인시던트 확인 실패 — {}", exception.getErrorCode().name());
            redirectAttributes.addFlashAttribute("flashError",
                    errorMessageResolver.resolve(exception.getErrorCode(), exception.messageArgs()));
        }
        return "redirect:/monitor/incidents";
    }

    @PostMapping("/monitor/incidents/{incidentId}/resolve")
    public String resolve(@PathVariable UUID incidentId,
                          @RequestParam(required = false) String resolutionNote,
                          RedirectAttributes redirectAttributes) {
        try {
            incidentAdminService.resolve(incidentId, normalize(resolutionNote));
            redirectAttributes.addFlashAttribute("flashSuccess", "해결 처리했습니다.");
        } catch (BusinessException exception) {
            log.warn("인시던트 해결 실패 — {}", exception.getErrorCode().name());
            redirectAttributes.addFlashAttribute("flashError",
                    errorMessageResolver.resolve(exception.getErrorCode(), exception.messageArgs()));
        }
        return "redirect:/monitor/incidents";
    }

    /** 빈 입력은 "메모 없음" 이다 — 공백 문자열을 저장하지 않는다. */
    private String normalize(String resolutionNote) {
        if (resolutionNote == null || resolutionNote.isBlank()) {
            return null;
        }
        return resolutionNote.strip();
    }
}
