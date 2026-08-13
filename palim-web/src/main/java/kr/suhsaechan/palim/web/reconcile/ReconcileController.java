package kr.suhsaechan.palim.web.reconcile;

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

    @GetMapping("/reconcile")
    public String list(Model model) {
        model.addAttribute("title", "재고 대조");
        model.addAttribute("definitions", definitions.findByIsActiveTrueOrderByCode());
        return "reconcile/list";
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
