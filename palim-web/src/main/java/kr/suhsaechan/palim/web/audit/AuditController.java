package kr.suhsaechan.palim.web.audit;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;
import kr.suhsaechan.palim.audit.AuditSearchCondition;
import kr.suhsaechan.palim.audit.AuditSearchField;
import kr.suhsaechan.palim.audit.AuditService;
import kr.suhsaechan.palim.audit.AuditType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 감사 로그 화면 (Redmine #208170 감사로그 캡처 대응).
 *
 * <h2>필터·페이징은 쿼리스트링 기반 서버 렌더링이다</h2>
 *
 * <p>AJAX 로 하면 북마크 · 뒤로가기 · 새로고침을 잃고 CSP 에 스크립트가 늘어난다. 감사 로그는
 * "이 조건의 기록을 다시 보여달라" 는 요구가 잦아 <b>조회 조건이 URL 에 남는 것 자체가 기능</b>이다.
 */
@Controller
@RequiredArgsConstructor
public class AuditController {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGE = 1_000;

    private final AuditService auditService;

    @GetMapping("/audit")
    public String list(
            @RequestParam(required = false) DatePreset preset,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Set<AuditType> types,
            @RequestParam(required = false) AuditSearchField field,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            Model model) {

        DatePreset activePreset = preset != null ? preset : DatePreset.TODAY;
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        LocalDate fromDate;
        LocalDate toDate;
        if (activePreset == DatePreset.CUSTOM && from != null && to != null) {
            fromDate = from;
            toDate = to;
        } else {
            if (activePreset == DatePreset.CUSTOM) {
                // 직접 지정인데 날짜가 없다 — 오늘로 되돌린다.
                activePreset = DatePreset.TODAY;
            }
            fromDate = activePreset.from(today);
            toDate = activePreset.to(today);
        }
        if (toDate.isBefore(fromDate)) {
            LocalDate swap = fromDate;
            fromDate = toDate;
            toDate = swap;
        }

        // 유형 미지정이면 인증+변경만 보여준다 (07-DECISIONS 018). 조회(VIEW)까지 전부 기록하되
        // 기본 화면에서는 숨긴다 — 조회 행이 로그인·변경 기록을 묻어버리기 때문이다.
        // 조회를 보려면 유형 필터에서 명시적으로 선택한다.
        Set<AuditType> effectiveTypes = types != null ? types
                : EnumSet.complementOf(EnumSet.of(AuditType.VIEW));

        AuditSearchCondition condition = new AuditSearchCondition(
                fromDate.atStartOfDay(BUSINESS_ZONE).toInstant(),
                // 종료일 포함 — 다음 날 자정 미만.
                toDate.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant(),
                effectiveTypes,
                field != null ? field : AuditSearchField.ACTOR_ID,
                keyword);

        int safePage = Math.clamp(page, 0, MAX_PAGE);
        Page<AuditLogView> logs = auditService
                .search(condition, PageRequest.of(safePage, PAGE_SIZE))
                .map(AuditLogView::from);

        model.addAttribute("title", "감사 로그");
        model.addAttribute("logs", logs);
        model.addAttribute("presets", DatePreset.values());
        model.addAttribute("selectedPreset", activePreset);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("allTypes", EnumSet.allOf(AuditType.class));
        model.addAttribute("selectedTypes", effectiveTypes);
        model.addAttribute("fields", AuditSearchField.values());
        model.addAttribute("selectedField", field != null ? field : AuditSearchField.ACTOR_ID);
        model.addAttribute("keyword", keyword);
        return "audit/list";
    }
}
