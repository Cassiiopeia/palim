package kr.suhsaechan.palim.web.reconcile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
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
    public String units(@RequestParam(required = false) UUID definitionId, Model model) {
        model.addAttribute("title", "품목 잇기");
        model.addAttribute("units", unitService.activeUnits());
        model.addAttribute("pending", unitService.pending());
        addCandidates(definitionId, model);
        return "reconcile/units";
    }

    /**
     * 아직 안 이어진 품목을 묶어 보여준다.
     *
     * <p>대조 정의가 있어야 «어느 두 곳» 을 볼지 안다. 정의가 없으면 후보를 만들 수 없고,
     * 그것은 오류가 아니라 <b>아직 할 차례가 아닌 것</b>이다.
     *
     * <p><b>정의가 여럿일 때 코드가 하나를 고르지 않는다.</b> 예전에는 코드순 첫 번째를
     * 집었다. 그러면 정의 이름만 바꿔도 후보 목록이 이유 없이 통째로 달라지는데, 화면
     * 어디에도 «지금 어느 대조를 보고 있는지» 가 없어 사람은 그 사실조차 모른다.
     *
     * <p>하나뿐이면 고를 것이 없으므로 그냥 쓴다 — 그건 짐작이 아니다. 여럿이면 <b>묻는다.</b>
     *
     * <p>이어 둔 것 자체는 대조마다 갈리지 않는다({@code (tenant, source, item_ref)} 로 온
     * 시스템이 공유한다). 그래서 어느 대조를 골라 이었든 결과는 모든 대조에 함께 쓰인다 —
     * 여기서 고르는 것은 «무엇을 이을지 찾아볼 범위» 일 뿐이다.
     */
    private void addCandidates(UUID definitionId, Model model) {
        List<ReconcileDefinition> active = definitions.findByIsActiveTrueOrderByCode();
        model.addAttribute("definitions", active);

        ReconcileDefinition definition = pick(definitionId, active);
        if (definition == null) {
            // 고를 것이 여럿인데 안 골랐거나, 없는 것을 가리켰다. 둘 다 화면이 목록을 그려
            // 묻는다 — 아무것도 안 그리면 「눌렀는데 아무 일도 안 났다」 가 된다.
            model.addAttribute("mustPickDefinition", !active.isEmpty());
            return;
        }
        model.addAttribute("definition", definition);

        UUID tenantId = TenantContext.current();
        Instant baseAt = aggregator.latestBaseAt(tenantId, definition.getLeftSource())
                .orElse(null);
        if (baseAt == null) {
            return;
        }
        model.addAttribute("candidates", finder.suggest(
                tenantId, definition.getLeftSource(), definition.getRightSource(), baseAt));
    }

    /** 고른 것이 있으면 그것, 하나뿐이면 그것, 여럿인데 안 골랐으면 {@code null}. */
    private ReconcileDefinition pick(UUID definitionId, List<ReconcileDefinition> active) {
        if (definitionId != null) {
            return active.stream()
                    .filter(candidate -> candidate.getId().equals(definitionId))
                    .findFirst()
                    .orElse(null);
        }
        return active.size() == 1 ? active.getFirst() : null;
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
                                      @RequestParam(required = false) UUID definitionId,
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
        return backToUnits(definitionId);
    }

    /** 사람이 보고 맞다고 했다. 이 시점부터 대조 합산에 들어간다. */
    @PostMapping("/reconcile/units/members/{memberId}/confirm")
    public String confirm(@PathVariable UUID memberId,
                          @RequestParam(required = false) UUID definitionId,
                          RedirectAttributes redirect) {
        unitService.confirm(memberId);
        redirect.addFlashAttribute("flashSuccess", "확인했습니다. 이제 대조에 들어갑니다.");
        return backToUnits(definitionId);
    }

    /**
     * 잘못 이었으면 끊는다.
     *
     * <p>끊는 길이 없으면 막다른 길이 된다 — 한 품목은 한 단위에만 속하므로, 잘못 붙인 것을
     * 남겨 두면 그 품목을 다시 붙일 수 없다.
     */
    @PostMapping("/reconcile/units/members/{memberId}/detach")
    public String detach(@PathVariable UUID memberId,
                         @RequestParam(required = false) UUID definitionId,
                         RedirectAttributes redirect) {
        unitService.detach(memberId);
        redirect.addFlashAttribute("flashSuccess", "연결을 끊었습니다. 다시 이을 수 있습니다.");
        return backToUnits(definitionId);
    }

    /**
     * 고른 대조를 <b>들고</b> 화면으로 돌아간다.
     *
     * <p>안 들고 가면 대조가 둘 이상일 때 한 건 처리할 때마다 「어느 대조에서 찾을까요」 로
     * 되돌아가 다시 고르게 된다. 이을 것이 스무 개면 스무 번 고른다.
     */
    private String backToUnits(UUID definitionId) {
        return definitionId == null
                ? "redirect:/reconcile/units"
                : "redirect:/reconcile/units?definitionId=" + definitionId;
    }
}
