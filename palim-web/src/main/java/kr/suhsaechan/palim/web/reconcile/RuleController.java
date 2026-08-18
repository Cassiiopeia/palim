package kr.suhsaechan.palim.web.reconcile;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.rule.NormalizationPreview;
import kr.suhsaechan.palim.reconcile.rule.NormalizationRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 이름 다듬기 규칙 화면.
 *
 * <p>이 화면이 없어서 <b>규칙이 DB 에만 있었다.</b> 그러면 이 프로그램은 한 회사에서만 돈다 —
 * 표기 습관은 회사마다 다른데, 자기 습관에 맞는 규칙을 넣을 자리가 없으면 다른 곳에 가져다
 * 놓는 순간 「묶을 수 있는 것」 이 늘 비어 있게 되고, 품목을 전부 손으로 이어야 한다.
 *
 * <p><b>저장 전에 걸어 볼 수 있다.</b> 규칙을 잘못 고쳤을 때 눈에 보이는 것은 「이을 수 있는
 * 것이 줄었다」 뿐이고 왜인지는 어디에도 안 나온다. 지금 담긴 실제 품명이 어떻게 바뀌는지
 * 그 자리에서 보여주면 그 왕복이 사라진다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class RuleController {

    private final NormalizationRuleService ruleService;
    private final NormalizationPreview preview;
    private final ErrorMessageResolver errorMessages;

    @GetMapping("/reconcile/rules")
    public String rules(@RequestParam(required = false) String name,
                        @RequestParam(required = false) String pattern,
                        @RequestParam(required = false) String replacement,
                        Model model) {
        model.addAttribute("title", "이름 다듬기 규칙");
        model.addAttribute("rules", ruleService.all());
        model.addAttribute("name", name == null ? "" : name);
        model.addAttribute("pattern", pattern == null ? "" : pattern);
        model.addAttribute("replacement", replacement == null ? "" : replacement);
        addPreview(pattern, replacement, model);
        return "reconcile/rules";
    }

    /**
     * 지금 담긴 품명에 걸어 본 결과.
     *
     * <p>정규식이 잘못됐거나 계산이 안 끝나도 <b>화면은 열려야 한다.</b> 안 그러면 그 규칙을
     * 고칠 자리조차 사라진다.
     */
    private void addPreview(String pattern, String replacement, Model model) {
        try {
            if (pattern == null || pattern.isBlank()) {
                model.addAttribute("preview", preview.preview(null));
                return;
            }
            preview.validate(pattern);
            model.addAttribute("preview", preview.preview(NormalizationPreview.candidate(
                    TenantContext.current(), "미리보기", pattern,
                    replacement == null ? "" : replacement, Integer.MAX_VALUE)));
            model.addAttribute("previewingCandidate", true);
        } catch (BusinessException e) {
            model.addAttribute("previewError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
            model.addAttribute("preview", List.of());
        }
    }

    @PostMapping("/reconcile/rules")
    public String create(@RequestParam String name,
                         @RequestParam String pattern,
                         @RequestParam(required = false) String replacement,
                         RedirectAttributes redirect) {
        try {
            ruleService.create(name, pattern, replacement == null ? "" : replacement);
            redirect.addFlashAttribute("flashSuccess",
                    "「%s」 규칙을 넣었습니다. 품목 묶기 화면에서 결과를 확인하세요.".formatted(name));
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
        }
        return "redirect:/reconcile/rules";
    }

    @PostMapping("/reconcile/rules/{id}")
    public String update(@PathVariable UUID id,
                         @RequestParam String name,
                         @RequestParam String pattern,
                         @RequestParam(required = false) String replacement,
                         RedirectAttributes redirect) {
        try {
            ruleService.update(id, name, pattern, replacement == null ? "" : replacement);
            redirect.addFlashAttribute("flashSuccess", "규칙을 고쳤습니다.");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
        }
        return "redirect:/reconcile/rules";
    }

    @PostMapping("/reconcile/rules/{id}/toggle")
    public String toggle(@PathVariable UUID id, RedirectAttributes redirect) {
        var rule = ruleService.toggle(id);
        redirect.addFlashAttribute("flashSuccess",
                rule.isActive() ? "규칙을 켰습니다." : "규칙을 껐습니다.");
        return "redirect:/reconcile/rules";
    }

    @PostMapping("/reconcile/rules/{id}/move")
    public String move(@PathVariable UUID id,
                       @RequestParam(defaultValue = "true") boolean up,
                       RedirectAttributes redirect) {
        ruleService.move(id, up);
        redirect.addFlashAttribute("flashSuccess", "순서를 바꿨습니다.");
        return "redirect:/reconcile/rules";
    }

    @PostMapping("/reconcile/rules/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes redirect) {
        ruleService.delete(id);
        redirect.addFlashAttribute("flashSuccess", "규칙을 지웠습니다.");
        return "redirect:/reconcile/rules";
    }
}
