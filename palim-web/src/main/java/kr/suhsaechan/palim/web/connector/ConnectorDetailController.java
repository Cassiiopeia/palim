package kr.suhsaechan.palim.web.connector;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.suhsaechan.palim.common.BaseAtGranularity;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorMapping;
import kr.suhsaechan.palim.connector.define.ConnectorMappingRepository;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.Intake;
import kr.suhsaechan.palim.connector.define.MappingStatus;
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
 * 시스템 하나를 손보는 화면.
 *
 * <p>목록이 진입점이고 여기가 그 아래다. 처음 붙일 때는 「연결 → 칸 맞추기」를 한 호흡으로
 * 가지만, 이미 붙여 둔 것을 손볼 때 그 긴 흐름을 다시 타게 하면 안 된다 — 비밀번호만 바꾸고
 * 싶은 사람에게 칸 맞추기를 다시 시킬 이유가 없다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ConnectorDetailController {

    private final ConnectorRepository connectorRepository;
    private final ConnectorAdminService adminService;
    private final ConnectorQueryService queryService;
    private final ConnectorMappingRepository mappingRepository;
    private final ErrorMessageResolver errorMessages;

    private static final int RECENT_RUN_LIMIT = 1;
    private static final int MAX_HOUR = 23;
    private static final int MAX_MINUTE = 59;

    @GetMapping("/connectors/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        Connector connector = adminService.connector(id);
        Optional<ConnectorMapping> active = mappingRepository
                .findByConnectorIdAndIntakeAndStatus(id, Intake.AUTO, MappingStatus.ACTIVE);
        List<RunSummary> runs = queryService.runs(id, RECENT_RUN_LIMIT);

        model.addAttribute("title", connector.getName());
        model.addAttribute("view", ConnectorDetailView.of(
                connector,
                active.isPresent(),
                active.map(ConnectorMapping::getVersion).orElse(null),
                runs.isEmpty() ? null : runs.get(0),
                // 파일 길의 칸이 없으면 올려도 전 행이 실패한다. 올리기 «전에» 말해야 한다.
                adminService.activeFieldCount(id, Intake.FILE)));
        // 고를 수 있는 눈금은 «코드가» 안다. 화면이 enum 을 직접 부르면 그 판단이 템플릿으로 샌다.
        model.addAttribute("granularities", BaseAtGranularity.values());
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

        // 형식·범위를 저장 전에 막는다. 여기서 안 거르면 "0 61 25 * * *" 같은 파싱 불가능한
        // cron 이 그대로 저장되고, ConnectorScheduler.due() 가 매분 CronExpression.parse() 에서
        // 터진다. 커넥터별로 예외를 잡으므로 스케줄러 전체가 죽지는 않지만, 그 커넥터는
        // 영구히 건너뛰어지고 로그에 매분 ERROR 가 쌓인다 — 사장님은 "왜 자료가 안 오지"로만 안다.
        Integer h = parseInRange(hour, MAX_HOUR);
        Integer m = (minute == null || minute.isBlank()) ? Integer.valueOf(0)
                : parseInRange(minute, MAX_MINUTE);
        if (h == null || m == null) {
            // 폼 흐름이라 예외를 던지지 않는다 — 던지면 전역 핸들러가 JSON 오류를 돌려주는데,
            // 여기는 브라우저 폼 제출이라 화면 대신 JSON 이 뜨면 안 된다.
            redirectAttributes.addFlashAttribute("flashError",
                    errorMessages.resolve(ErrorCode.INVALID_INPUT, "수집 시각"));
            return "redirect:/connectors/" + id;
        }

        connector.schedule("0 %d %d * * *".formatted(m, h));
        connectorRepository.save(connector);

        redirectAttributes.addFlashAttribute("flashSuccess",
                "매일 %02d:%02d 에 가져옵니다.".formatted(h, m));
        return "redirect:/connectors/" + id;
    }

    /**
     * 기준 시각을 <b>어느 굵기로 남길지</b> 정한다.
     *
     * <p>담긴 재고는 (원천, 기준 시각, 품목, 창고, 로트) 로 구분된다. 그래서 눈금이 하루면
     * 하루에 두 번 담아도 <b>둘이 같은 칸을 차지해 뒤엣것이 앞엣것을 덮는다</b> — 오전 재고를
     * 나중에 볼 방법이 없고, 덮였다는 사실조차 남지 않는다.
     *
     * <p>그렇다고 무조건 잘게 두면 안 된다. 원천이 날짜만 주는데 시간 눈금으로 남기면 부른
     * 시각이 기준 시각이 되어, 같은 자료가 시각만 다른 여러 벌로 쌓인다. <b>원천이 실제로
     * 주는 해상도</b>에 맞춘다.
     */
    @PostMapping("/connectors/{id}/granularity")
    public String granularity(@PathVariable UUID id,
                              @RequestParam BaseAtGranularity granularity,
                              RedirectAttributes redirectAttributes) {
        Connector connector = adminService.connector(id);
        connector.changeBaseAtGranularity(granularity);
        connectorRepository.save(connector);

        log.info("기준 시각 눈금 변경 — 커넥터={} 눈금={}", connector.getCode(), granularity);
        redirectAttributes.addFlashAttribute("flashSuccess",
                "기준 시각을 «%s» 단위로 남깁니다.".formatted(granularity.getLabel()));
        return "redirect:/connectors/" + id;
    }

    /** {@code 0 ~ max} 범위의 숫자만 통과시킨다. 형식이 틀렸거나 범위 밖이면 {@code null}. */
    private Integer parseInRange(String value, int max) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return (parsed >= 0 && parsed <= max) ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
