package kr.suhsaechan.palim.web.setup;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 준비 상태판.
 *
 * <p>처음 오는 사람의 진입점이다. 흩어진 화면을 순서대로 잇고, 지금 막힌 곳을 짚어 준다.
 */
@Controller
@RequiredArgsConstructor
public class SetupController {

    private final SetupService setupService;

    @GetMapping("/setup")
    public String index(Model model) {
        model.addAttribute("steps", setupService.steps());
        return "setup/index";
    }
}
