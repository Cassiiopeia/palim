package kr.suhsaechan.palim.web.connector;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.connector.source.http.ApiAuthPreset;
import kr.suhsaechan.palim.connector.source.http.ApiProbeRegistry;
import kr.suhsaechan.palim.connector.source.http.ProbeReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 외부 시스템 연결 화면.
 *
 * <p>인증정보를 넣고 <b>한 번에 전 단계를 검증</b>한 뒤, 통과한 설정을 커넥터로 저장한다.
 * 단계를 나눠 여러 번 시도하게 만들지 않는 이유는 일부 시스템의 테스트용 인증키가
 * <b>한 번 성공하면 소진되기 때문</b>이다. 검증과 필드 확인이 한 번에 끝나야 한다.
 *
 * <p>입력한 비밀값은 화면으로 되돌리지 않는다. 저장 후에는 등록 여부만 보인다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ConnectionController {

    private final ApiProbeRegistry probes;
    private final ConnectionAdminService connectionService;

    @GetMapping("/connectors/connect")
    public String form(Model model) {
        model.addAttribute("form", new ConnectionForm());
        addPresets(model);
        return "connector/connect";
    }

    /**
     * 시스템별 안내 문구.
     *
     * <p>화면이 시스템을 바꿀 때 서버에 다녀오지 않고 문구만 갈아 끼우도록 미리 넘긴다.
     * 폼을 다시 제출하면 연결 테스트가 실행되고 <b>1회용 인증키가 그 자리에서 소진</b>된다.
     */
    private void addPresets(Model model) {
        model.addAttribute("presets", ApiAuthPreset.values());
        Map<String, Map<String, Object>> meta = new LinkedHashMap<>();
        for (ApiAuthPreset preset : ApiAuthPreset.values()) {
            meta.put(preset.name(), Map.of(
                    "label", preset.getAccountLabel(),
                    "hint", preset.getHint(),
                    "accountHelp", preset.getAccountHelp(),
                    "secretLabel", preset.getSecretLabel(),
                    "issueGuide", preset.getIssueGuide(),
                    "keyStages", preset.hasKeyStages(),
                    "manual", preset.needsManualEndpoint()));
        }
        model.addAttribute("presetMeta", meta);
    }

    /** 연결 검증. 결과는 단계별로 보여준다. */
    @PostMapping("/connectors/connect/test")
    public String test(@ModelAttribute("form") ConnectionForm form, Model model) {
        addPresets(model);
        // 결과가 언제 것인지 없으면, 화면에 남은 것이 방금 실행한 것인지 아까 것인지
        // 구분되지 않는다. 여러 번 시도할수록 헷갈린다.
        model.addAttribute("testedAt", LocalDateTime.now());
        try {
            ProbeReport report = probes.of(form.getPreset()).probe(form.toProbeRequest());
            model.addAttribute("report", report);
            if (!report.isSuccess()) {
                model.addAttribute("error", "연결에 실패했습니다. 아래 단계를 확인하세요.");
            }
        } catch (BusinessException e) {
            // 입력이 모자란 경우다. 원격 호출 전에 막힌 것이므로 단계 결과가 없다.
            model.addAttribute("error", e.getMessage());
        }
        // 비밀값은 화면으로 되돌리지 않는다. 다시 입력하게 하는 편이 안전하다.
        form.setSecret(null);
        return "connector/connect";
    }

    /** 검증을 통과한 설정을 커넥터로 저장한다. */
    @PostMapping("/connectors/connect/save")
    public String save(@ModelAttribute("form") ConnectionForm form,
                       RedirectAttributes redirectAttributes) {
        try {
            var connector = connectionService.saveConnection(form);
            redirectAttributes.addFlashAttribute("message",
                    "연동을 만들었습니다. 이제 매핑을 정의하세요.");
            return "redirect:/connectors/" + connector.getId() + "/mapping";
        } catch (BusinessException | IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/connectors/connect";
        }
    }
}
