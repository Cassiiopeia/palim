package kr.suhsaechan.palim.web.settings;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.suhsaechan.palim.common.config.SystemConfig;
import kr.suhsaechan.palim.common.config.SystemConfigService;
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
 * 시스템 설정 화면.
 *
 * <p>임계값·가중치·주기를 <b>재기동 없이</b> 바꾼다. 값 하나 고치는 데 재배포가 필요하면
 * 캘리브레이션(기준을 맞춰 가는 작업) 자체가 불가능하다.
 *
 * <p>변경 이력을 함께 보여준다 — 점수 순위가 갑자기 달라졌을 때 원인을 추적할 유일한 단서다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class SystemConfigController {

    private static final int HISTORY_LIMIT = 20;

    /** 화면에 보이는 카테고리 순서와 이름. 등록되지 않은 카테고리는 목록 끝에 원문으로 나온다. */
    private static final Map<String, String> CATEGORY_NAMES = new LinkedHashMap<>();

    static {
        CATEGORY_NAMES.put("INFLUENCER_SCORING", "인플루언서 점수 기준");
        CATEGORY_NAMES.put("INFLUENCER_TAXONOMY", "카테고리 체계");
        CATEGORY_NAMES.put("INFLUENCER_YOUTUBE", "유튜브 연동");
        CATEGORY_NAMES.put("INFLUENCER_BATCH", "자동 실행");
    }

    private final SystemConfigService systemConfigService;
    private final ErrorMessageResolver errorMessageResolver;

    @GetMapping("/settings/system")
    public String settings(@RequestParam(required = false) String category, Model model) {
        var all = systemConfigService.findAllEditable();

        var categories = new LinkedHashMap<String, String>();
        CATEGORY_NAMES.forEach((code, name) -> {
            if (all.stream().anyMatch(config -> config.getCategory().equals(code))) {
                categories.put(code, name);
            }
        });
        all.stream()
                .map(SystemConfig::getCategory)
                .distinct()
                .filter(code -> !categories.containsKey(code))
                .forEach(code -> categories.put(code, code));

        String selected = category != null && categories.containsKey(category)
                ? category
                : categories.keySet().stream().findFirst().orElse(null);

        model.addAttribute("title", "시스템 설정");
        model.addAttribute("categories", categories);
        model.addAttribute("selectedCategory", selected);
        model.addAttribute("configs", selected == null
                ? java.util.List.of()
                : all.stream().filter(config -> config.getCategory().equals(selected)).toList());
        return "settings/system";
    }

    /**
     * 값 변경.
     *
     * <p>타입·범위 검증은 서비스가 한다. 화면에서 잘못된 값이 들어가면 시스템 동작이 통째로
     * 바뀌므로(배점에 음수가 들어가는 등) 저장 시점에 막는다.
     */
    @PostMapping("/settings/system")
    public String update(@RequestParam String configKey,
                         @RequestParam String configValue,
                         @RequestParam(required = false) String category,
                         Principal principal,
                         RedirectAttributes redirectAttributes) {
        try {
            systemConfigService.update(configKey, configValue, principal.getName());
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "설정을 저장했습니다. 다음 채점부터 바로 반영됩니다.");
        } catch (BusinessException e) {
            log.warn("설정 변경 실패 — {}", configKey, e);
            redirectAttributes.addFlashAttribute("flashError", errorMessageResolver.resolve(e));
        }
        return category == null
                ? "redirect:/settings/system"
                : "redirect:/settings/system?category=" + category;
    }

    @GetMapping("/settings/system/history")
    public String history(@RequestParam String configKey, Model model) {
        model.addAttribute("title", "설정 변경 이력");
        model.addAttribute("configKey", configKey);
        model.addAttribute("histories", systemConfigService.findHistory(configKey, HISTORY_LIMIT));
        return "settings/system-history";
    }
}
