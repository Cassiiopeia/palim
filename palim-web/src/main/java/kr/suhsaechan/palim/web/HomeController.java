package kr.suhsaechan.palim.web;

import kr.suhsaechan.palim.web.home.HomeSummaryService;
import kr.suhsaechan.palim.web.setup.SetupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 진입 화면과 로그인 화면.
 *
 * <p>홈은 <b>같은 자리가 시간에 따라 다른 것을 보여준다.</b> 준비가 안 끝났으면 다음 한 걸음을,
 * 끝났으면 오늘 맞춰 본 결과를 띄운다. 순서를 안내하는 화면을 메뉴의 한 항목으로 두면 여러 기능
 * 중 하나로 보이기 때문에 여기가 그 일을 한다.
 */
@Controller
@RequiredArgsConstructor
public class HomeController {

    private final SetupService setupService;
    private final HomeSummaryService homeSummaryService;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("title", "홈");
        model.addAttribute("steps", setupService.steps());
        model.addAttribute("today", homeSummaryService.today());
        return "home";
    }

    /**
     * 로그인 화면.
     *
     * <p>Spring Security 기본 페이지를 쓰지 않는 이유는 콘텐츠 보안 정책 때문이다. 기본 페이지는
     * 인라인 스타일을 쓰는데, {@code style-src 'self'} 로 제한하면 스타일이 적용되지 않는다.
     */
    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
