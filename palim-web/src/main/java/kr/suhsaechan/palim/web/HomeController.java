package kr.suhsaechan.palim.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 진입 화면과 로그인 화면.
 *
 * <p>대시보드 실제 구현은 후속 이슈다. 지금은 레이아웃과 인증이 동작하는지 확인하는 수준이다.
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("title", "대시보드");
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
