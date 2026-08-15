package kr.suhsaechan.palim.web.connector;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.script.PostScript;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 후처리 스크립트 화면.
 *
 * <p>파이썬을 모르는 사람이 쓴다는 전제로 만든다 — <b>외울 것이 없어야 한다.</b> 무엇이
 * 들어오고 무엇을 돌려줘야 하는지가 편집 화면에 늘 보이고, 자주 하는 처리는 눌러서 넣는다.
 *
 * <p>시험 화면을 따로 두지 않는다. 「담길 모습」 표가 이미 있고, 스크립트는 담기 직전에
 * 도므로 <b>그 표가 곧 스크립트를 거친 결과</b>다.
 */
@Controller
@RequiredArgsConstructor
public class PostScriptController {

    private final PostScriptAdminService scripts;
    private final ConnectorAdminService connectors;
    private final ErrorMessageResolver errorMessages;

    @GetMapping("/connectors/{id}/scripts/new")
    public String createForm(@PathVariable UUID id, Model model) {
        Connector connector = connectors.connector(id);
        model.addAttribute("title", connector.getName() + " · 후처리 스크립트");
        model.addAttribute("connector", connector);
        model.addAttribute("script", null);
        model.addAttribute("body", PostScriptExamples.STARTER);
        model.addAttribute("examples", PostScriptExamples.all());
        model.addAttribute("inputFields", connectors.targetFields(id));
        return "connector/script-edit";
    }

    @GetMapping("/connectors/{id}/scripts/{scriptId}")
    public String editForm(@PathVariable UUID id, @PathVariable UUID scriptId, Model model) {
        Connector connector = connectors.connector(id);
        PostScript script = scripts.get(scriptId);
        model.addAttribute("title", connector.getName() + " · " + script.getName());
        model.addAttribute("connector", connector);
        model.addAttribute("script", script);
        model.addAttribute("body", script.getBody());
        model.addAttribute("examples", PostScriptExamples.all());
        model.addAttribute("inputFields", connectors.targetFields(id));
        return "connector/script-edit";
    }

    @PostMapping("/connectors/{id}/scripts")
    public String save(@PathVariable UUID id,
                       @RequestParam(required = false) UUID scriptId,
                       @RequestParam String name,
                       @RequestParam String body,
                       RedirectAttributes redirect) {
        Connector connector = connectors.connector(id);
        try {
            PostScript saved = scripts.save(connector.getTenantId(), id, scriptId, name, body);
            redirect.addFlashAttribute("flashSuccess",
                    "「%s」 을(를) 저장했습니다. 시험 실행으로 결과를 확인하세요.".formatted(
                            saved.getName()));
            return "redirect:/connectors/" + id + "/scripts/" + saved.getId();
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
            return "redirect:/connectors/" + id + "/mapping";
        }
    }

    /** 켜고 끄기. 지우지 않고 꺼 두면 「이게 문제인가」를 하나씩 꺼보며 찾을 수 있다. */
    @PostMapping("/connectors/{id}/scripts/{scriptId}/enabled")
    public String changeEnabled(@PathVariable UUID id, @PathVariable UUID scriptId,
                                @RequestParam(defaultValue = "false") boolean enabled,
                                RedirectAttributes redirect) {
        scripts.changeEnabled(scriptId, enabled);
        redirect.addFlashAttribute("flashSuccess",
                enabled ? "이 스크립트를 다시 돌립니다." : "이 스크립트를 건너뜁니다.");
        return "redirect:/connectors/" + id + "/mapping";
    }

    /** 스크립트가 막혔을 때를 위한 길. 한 칸씩 자리를 맞바꾼다. */
    @PostMapping("/connectors/{id}/scripts/{scriptId}/move")
    public String move(@PathVariable UUID id, @PathVariable UUID scriptId,
                       @RequestParam int delta) {
        scripts.move(id, scriptId, delta);
        return "redirect:/connectors/" + id + "/mapping";
    }

    /** 끌어다 놓았을 때. 화면이 보낸 순서 그대로 다시 매긴다. */
    @PostMapping("/connectors/{id}/scripts/order")
    public String reorder(@PathVariable UUID id, @RequestParam List<UUID> scriptIds) {
        scripts.reorder(id, scriptIds);
        return "redirect:/connectors/" + id + "/mapping";
    }

    @PostMapping("/connectors/{id}/scripts/{scriptId}/remove")
    public String remove(@PathVariable UUID id, @PathVariable UUID scriptId,
                         RedirectAttributes redirect) {
        scripts.remove(scriptId);
        // 실제로 없애지 않고 보관으로 내린다. 이 스크립트로 다듬어진 자료가 이미 담겨 있다.
        redirect.addFlashAttribute("flashSuccess",
                "목록에서 내렸습니다. 지난 기록은 그대로 남습니다.");
        return "redirect:/connectors/" + id + "/mapping";
    }
}
