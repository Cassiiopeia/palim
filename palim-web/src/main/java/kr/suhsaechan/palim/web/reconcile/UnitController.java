package kr.suhsaechan.palim.web.reconcile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.engine.SnapshotAggregator;
import kr.suhsaechan.palim.reconcile.match.MatchCandidateFinder;
import kr.suhsaechan.palim.reconcile.match.SourceItemBrowser;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnit;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitMember;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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
    private final SourceItemBrowser browser;

    @GetMapping("/reconcile/units")
    public String units(@RequestParam(required = false) UUID definitionId,
                        @ModelAttribute PickForm form, Model model) {
        model.addAttribute("title", "품목 잇기");
        model.addAttribute("units", unitService.activeUnits());
        model.addAttribute("pending", pendingUnits());
        addCandidates(definitionId, model);
        addBrowser(form, model);
        return "reconcile/units";
    }

    /**
     * 확인을 기다리는 물건들 — <b>품명·수량과 함께.</b>
     *
     * <p>「erp · A0001 · 1」 만 보고는 맞다/아니다를 판단할 수 없다. 무슨 물건인지도, 무엇과
     * 묶였는지도 안 보이기 때문이다. 판단할 수 없는 확인 단계는 확인이 아니다.
     */
    private List<PendingUnitView> pendingUnits() {
        UUID tenantId = TenantContext.current();
        return unitService.pending().stream()
                .map(ReconcileUnitMember::getUnitId)
                .distinct()
                .map(unitId -> new PendingUnitView(unitId, unitNameOf(unitId),
                        unitService.membersOf(unitId).stream()
                                .map(member -> toPendingMember(tenantId, member))
                                .toList()))
                .toList();
    }

    /**
     * 담긴 재고에서 품명·수량을 찾아 붙인다.
     *
     * <p><b>못 찾아도 줄을 빼지 않는다.</b> 빼면 그 물건은 확인도 물리기도 못 하는 상태로
     * 화면에서 사라진다 — 큐에는 남아 있는데 손댈 자리가 없는 막다른 길이다. 원천이 품목코드를
     * 바꾸거나 그날 그 품목 재고가 없으면 실제로 그렇게 된다.
     */
    private PendingUnitView.Member toPendingMember(UUID tenantId, ReconcileUnitMember member) {
        return browser.find(tenantId, member.getSource(), member.getItemRef())
                .map(item -> new PendingUnitView.Member(
                        member.getSource(), member.getItemRef(), item.displayName(),
                        item.quantity(), member.getFactor(), true))
                .orElseGet(() -> new PendingUnitView.Member(
                        member.getSource(), member.getItemRef(), member.getItemRef(),
                        null, member.getFactor(), false));
    }

    private String unitNameOf(UUID unitId) {
        return unitService.activeUnits().stream()
                .filter(unit -> unit.getId().equals(unitId))
                .map(ReconcileUnit::getName)
                .findFirst()
                .orElse("이름 없음");
    }

    /**
     * 담기·빼기·찾기·쪽 넘김 — <b>전부 같은 폼의 제출</b>이다.
     *
     * <p>화면 안에 코드를 넣을 수 없어서(CSP) 담아 둔 것을 브라우저가 들고 있을 방법이 없고,
     * GET 폼은 제출할 때 기존 쿼리 문자열을 통째로 버려 주소에 담는 길도 막힌다. 그래서 한
     * 폼에 다 넣고 <b>무엇을 누르든 나머지가 함께 실려 온다.</b>
     *
     * <p>이 경로는 자료를 바꾸지 않는다 — 담아 둔 것을 고쳐 화면을 다시 그릴 뿐이다. 그래서
     * 리다이렉트하지 않고 바로 그린다.
     */
    @PostMapping("/reconcile/units")
    public String pick(@ModelAttribute PickForm form,
                       @RequestParam(required = false) UUID definitionId,
                       @RequestParam(required = false) String add,
                       @RequestParam(required = false) String drop,
                       Model model) {
        if (add != null && !add.isBlank() && !form.add(add)) {
            model.addAttribute("flashError",
                    "한 번에 %d개까지 담을 수 있습니다. 먼저 이은 뒤 이어서 담으세요."
                            .formatted(PickForm.PICK_LIMIT));
        }
        if (drop != null && !drop.isBlank()) {
            form.drop(drop);
        }
        return units(definitionId, form, model);
    }

    /**
     * 손으로 고르는 자리 — 좌·우 목록과 담아 둔 것.
     *
     * <p>이 자리가 없어서 <b>이름이 다른 품목은 이을 방법이 아예 없었다.</b> 자동 후보에 안
     * 뜨면 화면 어디에도 그 품목이 나타나지 않으므로, 사람이 「저 둘이 같은 거야」 라고 알고
     * 있어도 손댈 곳이 없다. 막다른 길이었다.
     *
     * <p><b>담은 것을 화면이 다시 조회한다.</b> 사람이 보낸 값을 그대로 그리지 않는다 —
     * 미리보기에 뜨는 품명·수량은 «지금 담겨 있는 값» 이어야 무엇을 잇고 있는지가 사실과
     * 어긋나지 않는다. 담긴 재고에 없는 품목이면 조용히 빠지지 않고 그 사실을 말한다.
     */
    private void addBrowser(PickForm form, Model model) {
        ReconcileDefinition definition = (ReconcileDefinition) model.getAttribute("definition");
        if (definition == null) {
            return;
        }
        UUID tenantId = TenantContext.current();
        SourceItemBrowser.LinkState state = form.linkState();

        model.addAttribute("leftItems", browser.browse(
                tenantId, definition.getLeftSource(), form.getLq(), state, form.getLp()));
        model.addAttribute("leftTotal", browser.count(
                tenantId, definition.getLeftSource(), form.getLq(), state));
        model.addAttribute("rightItems", browser.browse(
                tenantId, definition.getRightSource(), form.getRq(), state, form.getRp()));
        model.addAttribute("rightTotal", browser.count(
                tenantId, definition.getRightSource(), form.getRq(), state));
        model.addAttribute("pageSize", SourceItemBrowser.PAGE_SIZE);
        model.addAttribute("form", form);

        List<PickedView> picked = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (PickForm.Picked pick : form.picked()) {
            browser.find(tenantId, pick.source(), pick.itemRef())
                    .ifPresentOrElse(
                            item -> picked.add(new PickedView(item, pick.factor())),
                            () -> missing.add(pick.itemRef()));
        }
        model.addAttribute("picked", picked);
        model.addAttribute("missingPicks", missing);
        // 담은 것이 이미 어느 물건에 속해 있으면 이번 동작은 «합치기» 다. 화면이 그 사실을
        // 버튼 문구로 말해야, 새로 만드는 줄 알고 눌렀다가 남의 물건에 붙는 일이 없다.
        model.addAttribute("mergeInto", picked.stream()
                .filter(view -> view.item().linked())
                .map(view -> view.item().unitName())
                .distinct()
                .toList());
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
        // 「자료가 없다」와 「다 이었다」는 다른 사정인데 예전에는 한 문장으로 뭉뚱그렸다.
        // 어느 쪽이 담겼는지를 화면이 그대로 말할 수 있게 사실을 넘긴다.
        model.addAttribute("leftLoadedAt",
                aggregator.latestBaseAt(tenantId, definition.getLeftSource()).orElse(null));
        model.addAttribute("rightLoadedAt",
                aggregator.latestBaseAt(tenantId, definition.getRightSource()).orElse(null));
        model.addAttribute("candidates", finder.suggest(
                tenantId, definition.getLeftSource(), definition.getRightSource()));
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
     * 담아 둔 것을 <b>한 물건으로 잇는다.</b>
     *
     * <p>사람이 좌·우 목록에서 담고 미리보기를 본 뒤 누르는 길이다. 눈으로 확인한 뒤이므로
     * 바로 확정되어 대조에 들어간다 — 자동 후보에서 한꺼번에 묶는 길과 다른 점이 이것이고,
     * 버튼 문구가 그 차이를 말한다.
     *
     * <p>담은 것 중 하나가 이미 어느 물건에 속해 있으면 <b>그 물건에 나머지를 붙인다.</b>
     * 그것이 「합치기」 다.
     */
    @PostMapping("/reconcile/units/link")
    public String link(@ModelAttribute PickForm form,
                       @RequestParam(required = false) UUID definitionId,
                       RedirectAttributes redirect) {
        List<PickForm.Picked> picks = form.picked();
        try {
            var linked = unitService.link(
                    picks.stream()
                            .map(pick -> new ReconcileUnitService.Pick(
                                    pick.source(), pick.itemRef(), pick.factor()))
                            .toList(),
                    // 코드는 사람에게 묻지 않는다 — 사람이 신경 쓸 값이 아니고, 겹치면 저장이
                    // 막힌다. 이름은 사람이 정하되 비우면 첫 품목의 품명을 그대로 쓴다.
                    "U-" + UUID.randomUUID().toString().substring(0, 8),
                    nameFor(form, picks),
                    "EA");
            redirect.addFlashAttribute("flashSuccess",
                    "「%s」 로 이었습니다. 바로 대조에 들어갑니다.".formatted(linked.getName()));
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
        }
        return backToUnits(definitionId);
    }

    /**
     * 새로 만들 물건의 이름.
     *
     * <p>비우면 담은 첫 품목의 품명을 쓴다. <b>짐작이 아니다</b> — 지어낸 값이 아니라 담긴
     * 자료에 실제로 있는 이름이고, 이름 없이 만들면 목록에서 무엇인지 알 수 없다.
     */
    private String nameFor(PickForm form, List<PickForm.Picked> picks) {
        if (form.getNewName() != null && !form.getNewName().isBlank()) {
            return form.getNewName().trim();
        }
        return picks.stream()
                .findFirst()
                .flatMap(pick -> browser.find(TenantContext.current(), pick.source(),
                        pick.itemRef()))
                .map(SourceItemBrowser.BrowsedItem::displayName)
                .orElse("이름 없음");
    }

    /** 잘못 넣은 계수를 고친다. 수량이 통째로 어긋나므로 고칠 길이 있어야 한다. */
    @PostMapping("/reconcile/units/members/{memberId}/factor")
    public String changeFactor(@PathVariable UUID memberId,
                               @RequestParam BigDecimal factor,
                               @RequestParam(required = false) UUID definitionId,
                               RedirectAttributes redirect) {
        try {
            unitService.changeFactor(memberId, factor);
            redirect.addFlashAttribute("flashSuccess", "몇 개로 셀지 바꿨습니다.");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
        }
        return backToUnits(definitionId);
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
    @PostMapping("/reconcile/units/{unitId}/confirm")
    public String confirm(@PathVariable UUID unitId,
                          @RequestParam(required = false) UUID definitionId,
                          RedirectAttributes redirect) {
        // 단위 «통째로» 확정한다. 한쪽만 확정하면 합산이 「좌 120 · 우 0」 이 되어 대조가
        // 매일 유령 차이를 올리고, 사람은 그것을 매칭 문제가 아니라 재고 사고로 읽는다.
        unitService.confirmUnit(unitId);
        redirect.addFlashAttribute("flashSuccess", "확인했습니다. 이제 대조에 들어갑니다.");
        return backToUnits(definitionId);
    }

    /**
     * 「아닙니다」 — 확인 대기 중인 묶음을 통째로 물린다.
     *
     * <p>이미 확인해 둔 것은 건드리지 않는다. 그것까지 지우면 멀쩡히 돌던 연결이 함께
     * 사라져 그 물건의 재고가 대조에서 증발한다.
     */
    @PostMapping("/reconcile/units/{unitId}/discard")
    public String discard(@PathVariable UUID unitId,
                          @RequestParam(required = false) UUID definitionId,
                          RedirectAttributes redirect) {
        unitService.discardPending(unitId);
        redirect.addFlashAttribute("flashSuccess", "물렸습니다. 다시 이을 수 있습니다.");
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
