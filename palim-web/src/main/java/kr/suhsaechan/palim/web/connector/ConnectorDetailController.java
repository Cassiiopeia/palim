package kr.suhsaechan.palim.web.connector;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorMapping;
import kr.suhsaechan.palim.connector.define.ConnectorMappingRepository;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.MappingStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 시스템 하나를 손보는 화면.
 *
 * <p>목록이 진입점이고 여기가 그 아래다. 처음 붙일 때는 「연결 → 칸 맞추기」를 한 호흡으로
 * 가지만, 이미 붙여 둔 것을 손볼 때 그 긴 흐름을 다시 타게 하면 안 된다 — 비밀번호만 바꾸고
 * 싶은 사람에게 칸 맞추기를 다시 시킬 이유가 없다.
 */
@Controller
@RequiredArgsConstructor
public class ConnectorDetailController {

    private final ConnectorRepository connectorRepository;
    private final ConnectorAdminService adminService;
    private final ConnectorQueryService queryService;
    private final ConnectorMappingRepository mappingRepository;

    private static final int RECENT_RUN_LIMIT = 1;

    @GetMapping("/connectors/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        Connector connector = adminService.connector(id);
        Optional<ConnectorMapping> active =
                mappingRepository.findByConnectorIdAndStatus(id, MappingStatus.ACTIVE);
        List<RunSummary> runs = queryService.runs(id, RECENT_RUN_LIMIT);

        model.addAttribute("title", connector.getName());
        model.addAttribute("view", ConnectorDetailView.of(
                connector,
                active.isPresent(),
                active.map(ConnectorMapping::getVersion).orElse(null),
                runs.isEmpty() ? null : runs.get(0)));
        return "connector/detail";
    }

    /**
     * 매일 몇 시에 가져올지 정한다.
     *
     * <p>사장님은 cron 을 모른다. <b>시각만 고르게 하고 표현식은 여기서 만든다.</b> 비우면
     * 자동 수집을 끈다 — 켜기만 되고 끄기가 없으면 한 번 정한 뒤로 갇힌다.
     */
    @PostMapping("/connectors/{id}/schedule")
    public String schedule(@PathVariable UUID id,
                           @RequestParam(required = false) String hour,
                           @RequestParam(required = false) String minute,
                           RedirectAttributes redirectAttributes) {
        Connector connector = adminService.connector(id);

        if (hour == null || hour.isBlank()) {
            connector.schedule(null);
            connectorRepository.save(connector);
            redirectAttributes.addFlashAttribute("flashSuccess", "자동으로 가져오지 않습니다.");
            return "redirect:/connectors/" + id;
        }

        int h = Integer.parseInt(hour.trim());
        int m = (minute == null || minute.isBlank()) ? 0 : Integer.parseInt(minute.trim());
        connector.schedule("0 %d %d * * *".formatted(m, h));
        connectorRepository.save(connector);

        redirectAttributes.addFlashAttribute("flashSuccess",
                "매일 %02d:%02d 에 가져옵니다.".formatted(h, m));
        return "redirect:/connectors/" + id;
    }
}
