package kr.suhsaechan.palim.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 인증 후 진입 화면.
 *
 * <p>대시보드 실제 구현은 별도 이슈다. 지금은 로그인이 동작하는지 확인하는 최소 화면이다.
 * 로그인 페이지는 Spring Security 가 기본 제공하는 것을 그대로 쓴다.
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "home";
    }
}
