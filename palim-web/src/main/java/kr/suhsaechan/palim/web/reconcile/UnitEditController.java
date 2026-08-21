package kr.suhsaechan.palim.web.reconcile;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.reconcile.define.Pairing;
import kr.suhsaechan.palim.reconcile.filter.FilterService;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.match.BreakdownAxis;
import kr.suhsaechan.palim.reconcile.match.MatchBoard;
import kr.suhsaechan.palim.reconcile.match.UnitBreakdown;
import kr.suhsaechan.palim.reconcile.match.UnitSplitter;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnit;
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
 * 묶음 하나를 <b>손으로 고치는 자리.</b>
 *
 * <p>여태 묶음을 <b>통째로만</b> 다룰 수 있었다 — 통째로 만들고, 통째로 풀었다. 그래서 로트
 * 셋이 든 묶음에서 하나만 빼거나, 셋을 셋으로 쪼개거나, 두 묶음을 하나로 보는 일이 아예
 * 불가능했다. <b>코드가 정해 준 대로만 쓸 수 있는 상태</b>였다.
 *
 * <p>여기서는 품목 하나 단위로 손댄다 — 빼기(×), 넣기(+), 쪼개기, 합치기, 이름. 목록을
 * 벗어나 <b>한 묶음에만 집중</b>하게 두는 이유는, 표 안에서 다 하려면 표가 빽빽해져 정작
 * 견주는 일이 안 되기 때문이다(07-DECISIONS 044).
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class UnitEditController {

    /** 품목 넣기에서 한 번에 보여줄 후보 수. 닮은 순서라 앞쪽에 답이 있다. */
    private static final int PICK_LIMIT = 15;

    private final ReconcileUnitService unitService;
    private final UnitSplitter splitter;
    private final UnitBreakdown breakdowns;
    private final MatchBoard board;
    private final ReconcileDefinitionRepository definitions;
    private final ErrorMessageResolver errorMessages;
    private final FilterService filters;

    @GetMapping("/reconcile/units/{unitId}/edit")
    public String edit(@PathVariable UUID unitId,
                       @RequestParam(required = false) UUID definitionId,
                       @RequestParam(required = false) String axis,
                       @RequestParam(required = false) String add,
                       @RequestParam(required = false) String q,
                       Model model) {
        ReconcileDefinition definition = pick(definitionId);
        if (definition == null) {
            return "redirect:/reconcile/units";
        }
        UUID tenantId = TenantContext.current();
        ReconcileUnit unit = unitService.activeUnits().stream()
                .filter(candidate -> candidate.getId().equals(unitId))
                .findFirst()
                .orElse(null);
        if (unit == null) {
            return "redirect:/reconcile/units?definitionId=" + definition.getId();
        }

        BreakdownAxis using = breakdowns.axisOf(tenantId,
                axis == null || axis.isBlank() ? definition.getBreakdownAxis() : axis);

        model.addAttribute("title", "묶음 고치기");
        model.addAttribute("unit", unit);
        model.addAttribute("definition", definition);
        model.addAttribute("axes", breakdowns.axes(tenantId));
        model.addAttribute("axis", using);
        List<MemberView> all = membersOf(tenantId, definition, unitId);
        // 좌·우를 «여기서» 갈라 넘긴다. 화면에서 거르면 레코드 접근자 때문에 표현식이 터지고,
        // 터지면 200 인 채로 페이지가 잘린다(07-DECISIONS 저장된 함정).
        model.addAttribute("leftMembers", all.stream().filter(MemberView::left).toList());
        model.addAttribute("rightMembers", all.stream().filter(m -> !m.left()).toList());
        model.addAttribute("breakdown", breakdowns.of(tenantId, unitId, filters.pairingOf(definition),
                UnitBreakdown.At.now(), using));

        // 「+ 품목 넣기」 를 누른 쪽에만 고를 목록을 편다. 두 쪽을 다 펴 두면 화면이 늘 길다.
        if (add != null && !add.isBlank()) {
            model.addAttribute("addSource", add);
            model.addAttribute("q", q == null ? "" : q);
            model.addAttribute("candidates", board.mateCandidates(tenantId,
                    filters.pairingOf(definition),
                    add, unit.getName(), q, PICK_LIMIT));
        }
        // 합칠 상대. 자기 자신은 뺀다.
        model.addAttribute("otherUnits", unitService.activeUnits().stream()
                .filter(candidate -> !candidate.getId().equals(unitId))
                .toList());
        return "reconcile/unit-edit";
    }

    /** 이 묶음에 든 품목들 — 좌·우로 갈라서. 지금 담긴 재고의 수량과 함께. */
    private List<MemberView> membersOf(UUID tenantId, ReconcileDefinition definition, UUID unitId) {
        return unitService.membersOf(unitId).stream()
                .map(member -> {
                    // 그 원천에 걸린 창고 범위로 본다. 전 창고를 더한 수량을 보여주면
                    // 「이 묶음에 얼마나 있나」 가 대조 결과와 어긋난다.
                    var item = board.findItem(tenantId, member.getSource(), member.getItemRef(),
                            filters.pairingOf(definition).filterOf(member.getSource()));
                    return new MemberView(
                            member.getId(),
                            member.getSource(),
                            member.getItemRef(),
                            item.map(MatchBoard.Item::displayName).orElse(member.getItemRef()),
                            item.map(MatchBoard.Item::quantityText).orElse("—"),
                            member.getFactor(),
                            member.getSource().equals(definition.getLeftSource()),
                            item.isPresent());
                })
                .toList();
    }

    /**
     * 품목 하나를 이 묶음에서 <b>뺀다.</b>
     *
     * <p>이 길이 없어서 로트 셋 중 하나가 잘못 들어가면 <b>묶음을 통째로 풀고 다시 묶는</b>
     * 수밖에 없었다. 셋 중 하나 때문에 나머지 둘까지 다시 하는 것이다.
     */
    @PostMapping("/reconcile/units/members/{memberId}/remove")
    public String remove(@PathVariable UUID memberId,
                         @RequestParam UUID unitId,
                         @RequestParam(required = false) UUID definitionId,
                         RedirectAttributes redirect) {
        unitService.detach(memberId);
        redirect.addFlashAttribute("flashSuccess", "뺐습니다. 그 품목은 「할 일」 로 돌아갑니다.");
        return back(unitId, definitionId);
    }

    /** 품목 하나를 이 묶음에 넣는다. */
    @PostMapping("/reconcile/units/{unitId}/add")
    public String add(@PathVariable UUID unitId,
                      @RequestParam String token,
                      @RequestParam(required = false) UUID definitionId,
                      RedirectAttributes redirect) {
        int cut = token.indexOf('|');
        if (cut <= 0 || cut == token.length() - 1) {
            redirect.addFlashAttribute("flashError", "고른 품목을 알아보지 못했습니다.");
            return back(unitId, definitionId);
        }
        try {
            unitService.attach(unitId, token.substring(0, cut), token.substring(cut + 1),
                    BigDecimal.ONE);
            redirect.addFlashAttribute("flashSuccess", "넣었습니다.");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
        }
        return back(unitId, definitionId);
    }

    /** 고른 기준으로 갈라 여러 묶음으로 만든다. */
    @PostMapping("/reconcile/units/{unitId}/split")
    public String split(@PathVariable UUID unitId,
                        @RequestParam String axis,
                        @RequestParam(required = false) UUID definitionId,
                        RedirectAttributes redirect) {
        ReconcileDefinition definition = pick(definitionId);
        if (definition == null) {
            return "redirect:/reconcile/units";
        }
        UUID tenantId = TenantContext.current();
        try {
            List<ReconcileUnit> created = splitter.split(tenantId, unitId, filters.pairingOf(definition),
                    breakdowns.axisOf(tenantId, axis));
            if (created.isEmpty()) {
                redirect.addFlashAttribute("flashError",
                        "이 기준으로는 하나로 남습니다. 다른 기준을 골라 보세요.");
                return back(unitId, definitionId);
            }
            redirect.addFlashAttribute("flashSuccess",
                    "%d개 묶음으로 갈랐습니다.".formatted(created.size()));
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
            return back(unitId, definitionId);
        }
        return "redirect:/reconcile/units?definitionId=" + definitionId + "&tab=LINKED#board";
    }

    /**
     * 다른 묶음을 이 묶음에 <b>합친다.</b>
     *
     * <p>여태 「이미 서로 다른 묶음에 속한 품목입니다」 로 막히기만 했다. 막은 이유는 있었지만
     * <b>막기만 하고 할 길을 안 주면 그건 그냥 못 하는 일</b>이다.
     */
    @PostMapping("/reconcile/units/{unitId}/merge")
    public String merge(@PathVariable UUID unitId,
                        @RequestParam UUID otherUnitId,
                        @RequestParam(required = false) UUID definitionId,
                        RedirectAttributes redirect) {
        try {
            ReconcileUnit merged = unitService.merge(unitId, otherUnitId);
            redirect.addFlashAttribute("flashSuccess",
                    "「%s」 로 합쳤습니다.".formatted(merged.getName()));
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
        }
        return back(unitId, definitionId);
    }

    /**
     * 아직 안 묶인 줄을 <b>갈라서 묶기</b> — 그 전에 어떻게 갈라지는지 보여준다.
     *
     * <p>자동 후보는 이름이 닮은 것을 통째로 하나로 제안한다. 로트 셋이 든 줄은 「+50 하나」 로
     * 볼 수도 있고 「+24 · +26 · 맞음」 셋으로 볼 수도 있는데, <b>고를 자리가 없으면 코드가
     * 정해 버린다.</b>
     */
    @GetMapping("/reconcile/units/split-preview")
    public String splitPreview(@RequestParam UUID definitionId,
                               @RequestParam String row,
                               @RequestParam(required = false) String tab,
                               @RequestParam(required = false) String axis,
                               Model model) {
        ReconcileDefinition definition = pick(definitionId);
        if (definition == null) {
            return "redirect:/reconcile/units";
        }
        UUID tenantId = TenantContext.current();
        MatchBoard.Row found = board.findRow(tenantId, filters.pairingOf(definition), row).orElse(null);
        if (found == null) {
            return "redirect:/reconcile/units?definitionId=" + definitionId;
        }

        BreakdownAxis using = breakdowns.axisOf(tenantId,
                axis == null || axis.isBlank() ? definition.getBreakdownAxis() : axis);
        model.addAttribute("title", "나눠서 묶기");
        model.addAttribute("definition", definition);
        model.addAttribute("row", found);
        model.addAttribute("rowKey", row);
        model.addAttribute("tab", tab == null ? "TODO" : tab);
        model.addAttribute("axes", breakdowns.axes(tenantId));
        model.addAttribute("axis", using);
        // 아직 안 묶였으므로 «묶었다 치고» 어떻게 갈라지는지 계산한다. 실제로 묶지는 않는다.
        model.addAttribute("groups", splitter.previewUnlinked(found, using));
        return "reconcile/unit-split";
    }

    /** 미리 본 대로 갈라서 묶는다. */
    @PostMapping("/reconcile/units/split-link")
    public String splitLink(@RequestParam UUID definitionId,
                            @RequestParam String row,
                            @RequestParam String axis,
                            @RequestParam(required = false) String tab,
                            RedirectAttributes redirect) {
        ReconcileDefinition definition = pick(definitionId);
        if (definition == null) {
            return "redirect:/reconcile/units";
        }
        UUID tenantId = TenantContext.current();
        MatchBoard.Row found = board.findRow(tenantId, filters.pairingOf(definition), row).orElse(null);
        if (found == null) {
            redirect.addFlashAttribute("flashError",
                    "그 줄을 지금 자료에서 찾지 못했습니다. 화면을 새로 고친 뒤 다시 해 보세요.");
            return "redirect:/reconcile/units?definitionId=" + definitionId;
        }
        try {
            List<ReconcileUnit> created = splitter.linkSeparately(tenantId, found,
                    breakdowns.axisOf(tenantId, axis));
            redirect.addFlashAttribute("flashSuccess",
                    "%d개 묶음으로 나눠서 묶었습니다.".formatted(created.size()));
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
        }
        return "redirect:/reconcile/units?definitionId=" + definitionId
                + "&tab=" + (tab == null ? "TODO" : tab) + "#board";
    }

    private ReconcileDefinition pick(UUID definitionId) {
        List<ReconcileDefinition> active = definitions.findByIsActiveTrueOrderByCode();
        if (definitionId != null) {
            return active.stream()
                    .filter(candidate -> candidate.getId().equals(definitionId))
                    .findFirst()
                    .orElse(null);
        }
        return active.stream().findFirst().orElse(null);
    }

    private String back(UUID unitId, UUID definitionId) {
        return "redirect:/reconcile/units/" + unitId + "/edit"
                + (definitionId == null ? "" : "?definitionId=" + definitionId);
    }

    /**
     * 편집 화면에 보이는 품목 하나.
     *
     * @param left    왼쪽 시스템 것인가. 두 칸으로 갈라 놓아야 «무엇과 무엇이» 가 보인다
     * @param inStock 지금 담긴 재고에 있나. 없어도 지우지 않는다 — 빼는 자리가 사라진다
     */
    public record MemberView(UUID memberId, String source, String itemRef, String displayName,
                             String quantityText, BigDecimal factor, boolean left,
                             boolean inStock) {

        public boolean hasFactor() {
            return factor != null && factor.compareTo(BigDecimal.ONE) != 0;
        }
    }
}
