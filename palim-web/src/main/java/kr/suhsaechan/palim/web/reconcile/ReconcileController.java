package kr.suhsaechan.palim.web.reconcile;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.BaseAtGranularity;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.match.UnitBreakdown;
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

    private final UnitBreakdown breakdowns;
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
        model.addAttribute("granularities", BaseAtGranularity.values());
        return "reconcile/detail";
    }

    /**
     * <b>얼마나 굵게 견줄지</b> 정한다.
     *
     * <p>두 원천을 정확히 같은 순간에 뽑는 일은 없다. 전산은 기준일을 날짜로만 주고 물류는
     * 「지금 재고」 를 준다. 그래서 양쪽 기준 시각을 이 눈금으로 내려 <b>같은 칸</b>에 들어오면
     * 견준다.
     *
     * <p>담는 눈금보다 잘게 잡으면 <b>저장을 막는다.</b> 하루에 한 번 담는 원천은 늘 자정에
     * 찍히므로, 시간 눈금으로 견주면 상대가 그 칸에 있을 수 없다 — 대조는 매일 「기준 시각이
     * 다릅니다」 만 남기고 사람은 무엇이 잘못됐는지 알 길이 없다. 조용히 안 도는 대조를 만드느니
     * 여기서 막는 편이 낫다.
     */
    @PostMapping("/reconcile/{id}/granularity")
    public String changeGranularity(@PathVariable UUID id,
                                    @RequestParam BaseAtGranularity granularity,
                                    RedirectAttributes redirect) {
        ReconcileDefinition definition = definitions.findById(id).orElseThrow();
        UUID tenantId = TenantContext.current();

        for (String source : List.of(definition.getLeftSource(), definition.getRightSource())) {
            BaseAtGranularity collected = connectorQueryService.granularityOf(tenantId, source);
            if (granularity.isFinerThan(collected)) {
                redirect.addFlashAttribute("flashError",
                        ("«%s» 는 %s 단위로 담고 있어 %s 단위로는 맞춰 볼 수 없습니다. "
                                + "%s 단위 이상으로 고르거나, 담는 눈금을 먼저 바꾸세요.")
                                .formatted(source, collected.getLabel(), granularity.getLabel(),
                                        collected.getLabel()));
                return "redirect:/reconcile/" + id;
            }
        }

        definition.changeBaseAtGranularity(granularity);
        definitions.save(definition);

        log.info("대조 눈금 변경 — 정의={} 눈금={}", definition.getCode(), granularity);
        redirect.addFlashAttribute("flashSuccess",
                "«%s» 단위로 맞춰 봅니다.".formatted(granularity.getLabel()));
        return "redirect:/reconcile/" + id;
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

    /**
     * 대조 결과 한 회차.
     *
     * <p>줄을 <b>펼쳐서 품목별로 뜯어볼 수 있다.</b> 합계만으로는 「재고가 11개 빈다」 와
     * 「물류가 오래된 로트 3종을 이미 털었고 최신 로트는 맞는다」 를 구분할 수 없는데, 둘은
     * 전혀 다른 이야기이고 할 일도 다르다(07-DECISIONS 038).
     *
     * @param expand 뜯어볼 물건. 없으면 접힌 채로 그린다
     */
    @GetMapping("/reconcile/runs/{runId}")
    public String runDetail(@PathVariable UUID runId,
                            @RequestParam(required = false) UUID expand,
                            Model model) {
        ReconcileRun run = runs.findById(runId).orElseThrow();
        ReconcileDefinition definition = definitions.findById(run.getDefinitionId()).orElseThrow();
        UUID tenantId = TenantContext.current();

        List<ReconcileDiff> all = diffs.findByRunIdOrderByStateAscUnitCodeAsc(runId);
        // 물건 이름과 «든 품목 수» 를 한 번에 받아 붙인다. 줄마다 조회하면 줄 수만큼 늘어난다.
        var headers = breakdowns.headers(tenantId,
                all.stream().map(ReconcileDiff::getUnitId).filter(java.util.Objects::nonNull)
                        .distinct().toList(),
                definition.getLeftSource(), definition.getRightSource());

        List<DiffRowView> rows = all.stream()
                .map(diff -> {
                    var header = diff.getUnitId() == null ? null : headers.get(diff.getUnitId());
                    return DiffRowView.of(diff,
                            definition.getLeftSource(), definition.getRightSource(),
                            header == null ? null : header.name(),
                            header == null ? 0 : header.leftParts(),
                            header == null ? 0 : header.rightParts());
                })
                .toList();

        if (expand != null) {
            model.addAttribute("expandUnitId", expand);
            model.addAttribute("breakdown", breakdowns.of(tenantId, expand,
                    definition.getLeftSource(), definition.getRightSource(),
                    run.getLeftBaseAt(), run.getRightBaseAt(), run.getStartedAt()));
        }

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
