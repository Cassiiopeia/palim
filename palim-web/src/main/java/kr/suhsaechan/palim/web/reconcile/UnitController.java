package kr.suhsaechan.palim.web.reconcile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.engine.SnapshotAggregator;
import kr.suhsaechan.palim.reconcile.match.MatchCandidateFinder;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitService;
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
 * 품목 잇기 화면.
 *
 * <p>대조 로직을 다 만들어 놓고 <b>품목이 붙지 않아 못 쓰는 것</b>이 이런 과제의 전형적 실패다.
 * 그래서 이 화면이 대조 결과 화면만큼 중요하다.
 *
 * <p>후보를 보여주되 <b>확정은 사람이 한다.</b> 제안 상태를 확정처럼 보이게 하면 안 된다 —
 * 확인하지 않은 것이 확인된 것처럼 보이면 아무도 확인하지 않는다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class UnitController {

    private final ReconcileUnitService unitService;
    private final MatchCandidateFinder finder;
    private final SnapshotAggregator aggregator;
    private final ReconcileDefinitionRepository definitions;
    private final ErrorMessageResolver errorMessages;

    @GetMapping("/reconcile/units")
    public String units(Model model) {
        model.addAttribute("title", "품목 잇기");
        model.addAttribute("units", unitService.activeUnits());
        model.addAttribute("pending", unitService.pending());
        addCandidates(model);
        return "reconcile/units";
    }

    /**
     * 아직 안 이어진 품목을 묶어 보여준다.
     *
     * <p>대조 정의가 있어야 «어느 두 곳» 을 볼지 안다. 정의가 없으면 후보를 만들 수 없고,
     * 그것은 오류가 아니라 <b>아직 할 차례가 아닌 것</b>이다.
     */
    private void addCandidates(Model model) {
        definitions.findByIsActiveTrueOrderByCode().stream().findFirst().ifPresent(definition -> {
            UUID tenantId = TenantContext.current();
            Instant baseAt = aggregator.latestBaseAt(tenantId, definition.getLeftSource())
                    .orElse(null);
            if (baseAt == null) {
                return;
            }
            List<MatchCandidateFinder.MatchCandidate> candidates = finder.suggest(
                    tenantId, definition.getLeftSource(), definition.getRightSource(), baseAt);
            model.addAttribute("candidates", candidates);
            model.addAttribute("definition", definition);
        });
    }

    /**
     * 후보 묶음을 새 단위로 만든다.
     *
     * <p>묶음에 든 품목들을 <b>제안 상태로</b> 붙인다. 곧바로 확정하지 않는 이유는 정규화 규칙이
     * 틀렸을 때 엉뚱한 품목이 한 묶음에 들어오기 때문이다.
     */
    @PostMapping("/reconcile/units/from-candidate")
    public String createFromCandidate(@RequestParam String code, @RequestParam String name,
                                      @RequestParam(defaultValue = "EA") String baseUnit,
                                      @RequestParam List<String> sources,
                                      @RequestParam List<String> itemRefs,
                                      RedirectAttributes redirect) {
        try {
            var unit = unitService.create(code, name, baseUnit);
            for (int i = 0; i < itemRefs.size() && i < sources.size(); i++) {
                unitService.propose(unit.getId(), sources.get(i), itemRefs.get(i),
                        BigDecimal.ONE);
            }
            redirect.addFlashAttribute("flashSuccess",
                    "묶었습니다. 맞는지 확인하고 «확인» 을 눌러야 대조에 들어갑니다.");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
        }
        return "redirect:/reconcile/units";
    }

    /** 사람이 보고 맞다고 했다. 이 시점부터 대조 합산에 들어간다. */
    @PostMapping("/reconcile/units/members/{memberId}/confirm")
    public String confirm(@PathVariable UUID memberId, RedirectAttributes redirect) {
        unitService.confirm(memberId);
        redirect.addFlashAttribute("flashSuccess", "확인했습니다. 이제 대조에 들어갑니다.");
        return "redirect:/reconcile/units";
    }

    /**
     * 잘못 이었으면 끊는다.
     *
     * <p>끊는 길이 없으면 막다른 길이 된다 — 한 품목은 한 단위에만 속하므로, 잘못 붙인 것을
     * 남겨 두면 그 품목을 다시 붙일 수 없다.
     */
    @PostMapping("/reconcile/units/members/{memberId}/detach")
    public String detach(@PathVariable UUID memberId, RedirectAttributes redirect) {
        unitService.detach(memberId);
        redirect.addFlashAttribute("flashSuccess", "연결을 끊었습니다. 다시 이을 수 있습니다.");
        return "redirect:/reconcile/units";
    }
}
