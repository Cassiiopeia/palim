package kr.suhsaechan.palim.web.connector;

import java.math.BigDecimal;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 단위 환산 규칙 화면 (#55).
 *
 * <p>적재가 단위 때문에 막혔을 때 사람이 푸는 곳이다. 이 화면이 없으면 DB 를 직접 만져야 한다.
 */
@Controller
@RequiredArgsConstructor
public class UnitConversionController {

    private final UnitConversionAdminService adminService;

    @GetMapping("/connectors/units")
    public String list(Model model) {
        model.addAttribute("title", "단위 환산 규칙");
        model.addAttribute("rules", adminService.list());
        return "connector/units";
    }

    @PostMapping("/connectors/units")
    public String create(@RequestParam(required = false) String itemRef,
                         @RequestParam String fromUnit, @RequestParam String toUnit,
                         @RequestParam BigDecimal factor, RedirectAttributes redirect) {
        try {
            adminService.create(itemRef, fromUnit, toUnit, factor);
            redirect.addFlashAttribute("flashSuccess", "환산 규칙을 추가했습니다.");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/connectors/units";
    }

    @PostMapping("/connectors/units/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes redirect) {
        adminService.delete(id);
        redirect.addFlashAttribute("flashSuccess", "환산 규칙을 삭제했습니다.");
        return "redirect:/connectors/units";
    }
}
