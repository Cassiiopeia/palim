package kr.suhsaechan.palim.web.settings;

import java.security.Principal;
import kr.suhsaechan.palim.auth.PasswordPolicy;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 계정 설정 화면 — 비밀번호 변경 (09-SECURITY).
 *
 * <p>변경은 현재 비밀번호 재확인을 요구한다. 세션만 잡은 공격자가 비밀번호를 바꿔 발주자를
 * 쫓아내는 것을 막는다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AccountController {

    private static final String REDIRECT = "redirect:/settings/account";

    private final AccountAdminService accountAdminService;
    private final ErrorMessageResolver errorMessageResolver;

    @GetMapping("/settings/account")
    public String view(Model model, Principal principal) {
        model.addAttribute("title", "계정 설정");
        model.addAttribute("username", principal.getName());
        model.addAttribute("minLength", PasswordPolicy.MIN_LENGTH);
        return "settings/account";
    }

    @PostMapping("/settings/account/password")
    public String changePassword(@RequestParam String currentPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam String newPasswordConfirm,
                                 Principal principal,
                                 RedirectAttributes redirectAttributes) {
        if (!newPassword.equals(newPasswordConfirm)) {
            redirectAttributes.addFlashAttribute("flashError", "새 비밀번호가 서로 다릅니다.");
            return REDIRECT;
        }

        try {
            accountAdminService.changePassword(principal.getName(), currentPassword, newPassword);
            redirectAttributes.addFlashAttribute("flashSuccess", "비밀번호를 변경했습니다.");
        } catch (BusinessException exception) {
            log.warn("비밀번호 변경 실패 — {}", exception.getErrorCode().name());
            redirectAttributes.addFlashAttribute("flashError",
                    errorMessageResolver.resolve(exception.getErrorCode(), exception.messageArgs()));
        }
        return REDIRECT;
    }
}
