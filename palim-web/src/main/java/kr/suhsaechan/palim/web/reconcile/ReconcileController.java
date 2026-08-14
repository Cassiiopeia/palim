package kr.suhsaechan.palim.web.reconcile;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.engine.ReconcileEngine;
import kr.suhsaechan.palim.reconcile.run.DiffState;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiff;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiffRepository;
import kr.suhsaechan.palim.reconcile.run.ReconcileRun;
import kr.suhsaechan.palim.reconcile.run.ReconcileRunRepository;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.web.connector.ConnectorAdminService;
import kr.suhsaechan.palim.web.connector.ConnectorQueryService;
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
 * 재고 대조 화면.
 *
 * <p><b>확정된 차이를 위에, 관찰중을 아래에 둔다.</b> 지금 손댈 것과 지켜볼 것을 섞으면 둘 다
 * 안 보게 된다 — 목록이 길어질수록 위에서부터 훑다가 포기하기 때문이다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ReconcileController {

    private final ReconcileEngine engine;
    private final ReconcileDefinitionRepository definitions;
    private final ReconcileRunRepository runs;
    private final ReconcileDiffRepository diffs;
    private final ErrorMessageResolver errorMessages;
    private final ConnectorQueryService connectorQueryService;

    @GetMapping("/reconcile")
    public String list(Model model) {
        model.addAttribute("title", "대조 결과");
        model.addAttribute("definitions", definitions.findByIsActiveTrueOrderByCode());
        // 무엇과 무엇을 맞춰 볼지 고르려면 붙여 둔 시스템 목록이 필요하다.
        model.addAttribute("connectors",
                connectorQueryService.list(ConnectorAdminService.DEFAULT_TENANT));
        return "reconcile/list";
    }

    /**
     * 무엇과 무엇을 맞춰 볼지 정한다.
     *
     * <p>이 경로가 없어서 대조가 <b>영원히 돌지 않았다.</b> 정의가 0행이면 화면은 늘 비어 있고,
     * 매일 아침 도는 스케줄러도 빈 목록을 훑고 끝난다. 시스템을 다 붙여도 아무 일이 일어나지
     * 않는 이유가 이것이었다.
     *
     * <p>기준을 «전산 쪽» 과 «실물 쪽» 으로 나눠 묻는 이유는, 차이가 났을 때 <b>어느 쪽이
     * 많은지</b>가 곧 무엇을 해야 하는지이기 때문이다. 전산이 많으면 실물을 찾아봐야 하고,
     * 실물이 많으면 전산에 안 잡힌 입고가 있다는 뜻이다.
     */
    @PostMapping("/reconcile/definitions")
    public String createDefinition(@RequestParam String name,
                                   @RequestParam String leftSource,
                                   @RequestParam String rightSource,
                                   @RequestParam(required = false) String alertThreshold,
                                   RedirectAttributes redirectAttributes) {
        if (leftSource.equals(rightSource)) {
            redirectAttributes.addFlashAttribute("flashError",
                    "같은 시스템끼리는 맞춰 볼 수 없습니다. 서로 다른 둘을 고르세요.");
            return "redirect:/reconcile";
        }

        String code = "%s-%s".formatted(leftSource, rightSource);
        if (definitions.findByCode(code).isPresent()) {
            redirectAttributes.addFlashAttribute("flashError",
                    "이미 이 둘을 맞춰 보고 있습니다.");
            return "redirect:/reconcile";
        }

        ReconcileDefinition definition = definitions.save(ReconcileDefinition.of(
                TenantContext.current(), code, name.isBlank() ? code : name.trim(),
                leftSource, rightSource,
                // 원천마다 세는 단위가 달라도 기준 단위 수량은 맞춰 놓은 값이다. 그래서 이것으로
                // 견준다 — 원본 수량으로 비교하면 박스와 낱개를 빼는 일이 생긴다.
                "base_quantity",
                BigDecimal.ZERO,
                threshold(alertThreshold)));

        log.info("대조 대상 추가 — code={} 좌={} 우={}", code, leftSource, rightSource);
        redirectAttributes.addFlashAttribute("flashSuccess",
                "맞춰 볼 대상을 정했습니다. 품목을 이어 두면 매일 아침 스스로 맞춰 봅니다.");
        return "redirect:/reconcile/" + definition.getId();
    }

    /**
     * 알림 임계.
     *
     * <p>비워 두면 «알리지 않겠다» 는 뜻이다. 기본값을 임의로 정해 보내면 사람이 「왜 이게 오지」
     * 하고 알림 자체를 꺼 버린다.
     */
    private static BigDecimal threshold(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("알림 기준을 숫자로 읽지 못해 알리지 않기로 합니다 — 값='{}'", raw);
            return null;
        }
    }

    @GetMapping("/reconcile/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        ReconcileDefinition definition = definitions.findById(id).orElseThrow();
        model.addAttribute("title", definition.getName() + " · 대조");
        model.addAttribute("definition", definition);
        model.addAttribute("runs", runs.findByDefinitionIdOrderByStartedAtDesc(id));
        return "reconcile/detail";
    }

    /**
     * 지금 맞춰 본다.
     *
     * <p>기준 시각이 어긋나면 엔진이 실행을 실패로 남긴다. 예외로 튀지 않는 이유는 <b>그것도
     * 기록이어야</b> 사람이 «어제는 왜 안 돌았나» 에 답할 수 있기 때문이다.
     */
    @PostMapping("/reconcile/{id}/run")
    public String run(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            ReconcileRun run = engine.run(id);
            if (run.isSuccess()) {
                redirect.addFlashAttribute("flashSuccess",
                        "맞춰 봤습니다 — 차이 %d건, 아직 안 이어진 품목 %d건"
                                .formatted(run.getDiffCount(), run.getUnmatchedCount()));
            } else {
                redirect.addFlashAttribute("flashError", run.getMessage());
            }
            return "redirect:/reconcile/runs/" + run.getId();
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
            return "redirect:/reconcile/" + id;
        }
    }

    @GetMapping("/reconcile/runs/{runId}")
    public String runDetail(@PathVariable UUID runId, Model model) {
        ReconcileRun run = runs.findById(runId).orElseThrow();
        ReconcileDefinition definition = definitions.findById(run.getDefinitionId()).orElseThrow();

        List<ReconcileDiff> all = diffs.findByRunIdOrderByStateAscUnitCodeAsc(runId);
        List<DiffRowView> rows = all.stream()
                .map(diff -> DiffRowView.of(diff,
                        definition.getLeftSource(), definition.getRightSource()))
                .toList();

        model.addAttribute("title", definition.getName() + " · 결과");
        model.addAttribute("run", run);
        model.addAttribute("definition", definition);
        // 지금 손댈 것과 지켜볼 것을 갈라서 보여준다.
        model.addAttribute("confirmed", rows.stream()
                .filter(DiffRowView::confirmed).toList());
        model.addAttribute("observing", rows.stream()
                .filter(row -> !row.confirmed() && !row.unmatched()).toList());
        model.addAttribute("unmatched", rows.stream()
                .filter(DiffRowView::unmatched).toList());
        return "reconcile/run-detail";
    }

    /** 사람이 처리했다고 표시한다. */
    @PostMapping("/reconcile/diffs/{diffId}/resolve")
    public String resolve(@PathVariable UUID diffId,
                          @RequestParam(required = false) String note,
                          RedirectAttributes redirect) {
        ReconcileDiff diff = diffs.findById(diffId).orElseThrow();
        diff.resolve(note);
        diffs.save(diff);
        redirect.addFlashAttribute("flashSuccess", "처리한 것으로 표시했습니다.");
        return "redirect:/reconcile/runs/" + diff.getRunId();
    }

    /**
     * 알면서 두기로 한다.
     *
     * <p>다음 회차에 같은 차이가 다시 올라와도 조용히 둔다 — 이미 «알고 있다» 고 한 것을 매번
     * 다시 알리면 그것이 잡음이 된다.
     */
    @PostMapping("/reconcile/diffs/{diffId}/ignore")
    public String ignore(@PathVariable UUID diffId,
                         @RequestParam(required = false) String note,
                         RedirectAttributes redirect) {
        ReconcileDiff diff = diffs.findById(diffId).orElseThrow();
        diff.ignore(note);
        diffs.save(diff);
        redirect.addFlashAttribute("flashSuccess", "이 차이는 앞으로 알리지 않습니다.");
        return "redirect:/reconcile/runs/" + diff.getRunId();
    }
}
