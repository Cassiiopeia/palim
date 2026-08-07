package kr.suhsaechan.palim.web.settings;

import java.util.Map;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
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
 * 채널 설정 화면 (F-01, F-09).
 *
 * <p><b>도입 시 가장 먼저 필요한 화면이다.</b> 인증정보를 등록하고 채널을 활성화하지 않으면
 * 수집이 시작되지 않는다.
 *
 * <p>상태 변경은 전부 POST 다. CSRF 토큰이 필수이며 Thymeleaf 가 폼에 자동으로 넣는다.
 *
 * @deprecated 재고 시스템 동결(07-DECISIONS 023). 내비게이션에서 제거되었고 수정하지 않는다.
 */
@Deprecated
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChannelSettingController {

    private static final String REDIRECT = "redirect:/settings/channels";

    private final ChannelAdminService channelAdminService;
    private final ErrorMessageResolver errorMessageResolver;

    @GetMapping("/settings/channels")
    public String list(Model model) {
        model.addAttribute("title", "채널 설정");
        model.addAttribute("channels", channelAdminService.findAll());
        return "settings/channels";
    }

    /**
     * 인증정보를 등록·갱신한다.
     *
     * <p>입력값을 로그에 남기지 않는다. 인증정보가 로그 파일에 평문으로 흘러들면 암호화가
     * 무의미해진다 — 예외 메시지에도 값이 들어가지 않도록 주의한다.
     */
    @PostMapping("/settings/channels/{code}/credentials")
    public String updateCredentials(@PathVariable ChannelCode code,
                                    @RequestParam Map<String, String> formParams,
                                    RedirectAttributes redirectAttributes) {
        // CSRF 토큰과 프레임워크 파라미터를 제외한다.
        Map<String, String> credentials = new java.util.LinkedHashMap<>(formParams);
        credentials.remove("_csrf");

        try {
            channelAdminService.updateCredentials(code, credentials);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "%s 인증정보를 저장했습니다.".formatted(code.displayName()));
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return REDIRECT;
    }

    @PostMapping("/settings/channels/{code}/enable")
    public String enable(@PathVariable ChannelCode code, RedirectAttributes redirectAttributes) {
        try {
            channelAdminService.enable(code);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "%s 수집을 시작했습니다.".formatted(code.displayName()));
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return REDIRECT;
    }

    @PostMapping("/settings/channels/{code}/disable")
    public String disable(@PathVariable ChannelCode code, RedirectAttributes redirectAttributes) {
        try {
            channelAdminService.disable(code);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "%s 수집을 중단했습니다.".formatted(code.displayName()));
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return REDIRECT;
    }

    @PostMapping("/settings/channels/{code}/interval")
    public String changeInterval(@PathVariable ChannelCode code,
                                 @RequestParam int minutes,
                                 RedirectAttributes redirectAttributes) {
        try {
            channelAdminService.changeCollectInterval(code, minutes * 60);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "%s 수집 주기를 %d분으로 변경했습니다.".formatted(code.displayName(), minutes));
        } catch (BusinessException exception) {
            addError(redirectAttributes, exception);
        }
        return REDIRECT;
    }

    /**
     * 오류를 화면 문구로 변환한다.
     *
     * <p>{@code GlobalExceptionHandler} 는 REST 전용이라 화면 요청에는 JSON 이 나가면 안 된다.
     * 여기서 리다이렉트 + 안내 문구로 처리한다.
     */
    private void addError(RedirectAttributes redirectAttributes, BusinessException exception) {
        log.warn("채널 설정 변경 실패 — {}", exception.getErrorCode().name());
        redirectAttributes.addFlashAttribute("flashError",
                errorMessageResolver.resolve(exception.getErrorCode(), exception.messageArgs()));
    }
}
