package kr.suhsaechan.palim.web.setup;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 옛 준비 상태판 주소.
 *
 * <p>이 화면은 홈으로 합쳐졌다. 주소를 없애지 않고 넘기는 이유는 북마크한 사람이 있을 수
 * 있어서다 — 404 를 만나면 「없어졌나」로 읽힌다.
 */
@Controller
public class SetupController {

    @GetMapping("/setup")
    public String index() {
        return "redirect:/";
    }
}
