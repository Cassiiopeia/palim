package kr.suhsaechan.palim.web.reconcile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.reconcile.define.Pairing;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.engine.SnapshotAggregator;
import kr.suhsaechan.palim.reconcile.match.MatchBoard;
import kr.suhsaechan.palim.reconcile.match.UnpairedItem;
import kr.suhsaechan.palim.reconcile.match.UnitNameRule;
import kr.suhsaechan.palim.reconcile.match.UnitNaming;
import kr.suhsaechan.palim.reconcile.match.UnpairedService;
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
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 품목 묶기 화면 — <b>대조표 한 장.</b>
 *
 * <p>예전에는 화면이 셋으로 쪼개져 있었다. 「묶을 만한 것」 은 좌·우를 한 칸에 세로로 쌓아
 * 무엇이 무엇과 짝인지 안 보였고, 「직접 골라서 묶기」 는 좌·우 목록이 서로 무관하게 놓여
 * 견줄 수가 없었으며, 「정해 둔 품목」 은 코드와 이름만 보여줘 <b>방금 무슨 일이 일어났는지</b>
 * 알 수 없었다. 대조를 하러 온 사람에게 대조할 수 없는 화면을 준 것이다.
 *
 * <p>이제 한 줄이 묶음 하나다. 왼쪽 칸·오른쪽 칸·수량 차이가 나란히 있어 <b>잇기 전에</b>
 * 그 줄에서 판단이 선다. 그리고 그 판단이 곧 확인이므로 별도 확인 단계를 두지 않는다 —
 * 눈으로 견주지 못하는 화면에서만 확인 단계가 필요했다(07-DECISIONS 034).
 *
 * <p>여러 줄을 골라 한꺼번에 이을 수 있다. 품목이 수십 개일 때 한 줄씩 스무 번 누르는 것과
 * 고르고 한 번 누르는 것은 다른 일이다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class UnitController {

    /** 짝 찾기에서 한 번에 보여줄 반대쪽 후보 수. 닮은 순서로 정렬되므로 앞쪽에 답이 있다. */
    private static final int MATE_LIMIT = 12;

    private final ReconcileUnitService unitService;
    private final MatchBoard board;
    private final UnpairedService unpairedService;
    private final UnitNaming naming;
    private final SnapshotAggregator aggregator;
    private final ReconcileDefinitionRepository definitions;
    private final ErrorMessageResolver errorMessages;

    @GetMapping("/reconcile/units")
    public String units(@RequestParam(required = false) UUID definitionId,
                        @RequestParam(required = false) String tab,
                        @RequestParam(required = false) String q,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(required = false) String expand,
                        @RequestParam(required = false) String eq,
                        @RequestParam(defaultValue = "false") boolean all,
                        Model model) {
        model.addAttribute("title", "품목 묶기");
        model.addAttribute("tabs", MatchBoard.Tab.values());

        List<ReconcileDefinition> active = definitions.findByIsActiveTrueOrderByCode();
        model.addAttribute("definitions", active);

        ReconcileDefinition definition = pick(definitionId, active);
        if (definition == null) {
            // 고를 것이 여럿인데 안 골랐거나, 없는 것을 가리켰다. 둘 다 목록을 그려 묻는다 —
            // 아무것도 안 그리면 「눌렀는데 아무 일도 안 났다」 가 된다.
            model.addAttribute("mustPickDefinition", !active.isEmpty());
            return "reconcile/units";
        }

        UUID tenantId = TenantContext.current();
        MatchBoard.Tab current = MatchBoard.Tab.of(tab);
        MatchBoard.Board loaded = board.load(tenantId, Pairing.of(definition), current, q, page);

        model.addAttribute("definition", definition);
        model.addAttribute("board", loaded);
        model.addAttribute("tab", current);
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("reasons", UnpairedItem.Reason.values());
        // 「이 쪽 전부 고르기」. 열두 줄을 묶으려고 열두 번 누르게 두지 않는다.
        model.addAttribute("allSelected", all);
        // 「자료가 없다」 와 「다 이었다」 는 정반대 사정이고 할 일도 반대다. 어느 쪽이 담겼는지
        // 사실을 그대로 넘겨 화면이 구분해 말하게 한다.
        model.addAttribute("leftLoadedAt",
                aggregator.latestBaseAt(tenantId, definition.getLeftSource()).orElse(null));
        model.addAttribute("rightLoadedAt",
                aggregator.latestBaseAt(tenantId, definition.getRightSource()).orElse(null));

        // 담긴 품명과 어긋나는 이름들. 「다시 짓기」 를 권할 자리이자, 로트 날짜가 박힌
        // 옛 이름이 몇 개 남았는지 사람이 아는 유일한 길이다.
        model.addAttribute("renameSuggestions", naming.suggestions(tenantId,
                definition.getLeftSource(), definition.getRightSource()));

        addMateCandidates(tenantId, definition, loaded, expand, eq, model);
        return "reconcile/units";
    }

    /**
     * 펼친 줄 안에 그릴 <b>반대쪽 짝 후보.</b>
     *
     * <p>줄 «안» 에서 고르게 하는 이유는, 화면을 옮기면 무엇을 짝지으려 했는지를 사람이 머리로
     * 들고 다녀야 하기 때문이다. 왼쪽 품목과 후보 목록이 같은 줄에 있어야 견줄 수 있다.
     */
    private void addMateCandidates(UUID tenantId, ReconcileDefinition definition,
                                   MatchBoard.Board loaded, String expand, String eq,
                                   Model model) {
        if (expand == null || expand.isBlank()) {
            return;
        }
        MatchBoard.Row row = loaded.rows().stream()
                .filter(candidate -> candidate.key().equals(expand))
                .findFirst()
                .orElse(null);
        if (row == null) {
            // 이 쪽에 없는 줄을 펼치려 한다 — 쪽을 넘겼거나 다른 사람이 먼저 이었다.
            return;
        }
        // 한쪽만 있는 줄은 «반대쪽» 에서 고른다. 이미 양쪽이 다 있는 줄(묶어 둔 것에 더 담기)은
        // 어느 쪽에서 담을지 미리 정할 수 없으므로 양쪽을 다 보여준다.
        String opposite = row.left().isEmpty() ? definition.getLeftSource()
                : row.right().isEmpty() ? definition.getRightSource()
                : null;
        model.addAttribute("expandKey", expand);
        model.addAttribute("expandSource",
                opposite == null ? "양쪽" : opposite);
        model.addAttribute("eq", eq == null ? "" : eq);
        model.addAttribute("mates", board.mateCandidates(tenantId, Pairing.of(definition), opposite, row.displayName(), eq, MATE_LIMIT));
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
     * 고른 줄들을 <b>각각 한 묶음으로</b> 잇는다.
     *
     * <p>한 줄 = 한 묶음이므로 줄 셋을 고르면 묶음 셋이 생긴다. 셋을 하나로 합치는 것이
     * 아니다 — 그것은 서로 다른 묶음을 합치는 일이라 사람이 이름을 정해야 한다.
     *
     * <p><b>화면이 보낸 품목 목록을 받지 않는다.</b> 줄 열쇠만 받아 서버가 그 줄을 다시
     * 계산한다. 화면을 띄운 뒤 다른 사람이 그 품목을 이었을 수도, 주소를 손으로 고쳐 남의
     * 품목을 끼워 넣었을 수도 있다.
     */
    @PostMapping("/reconcile/units/link")
    public String link(@RequestParam(required = false) List<String> rows,
                       @RequestParam(required = false) String row,
                       @RequestParam(required = false) UUID definitionId,
                       @RequestParam(required = false) String tab,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       RedirectAttributes redirect) {
        ReconcileDefinition definition = requireDefinition(definitionId);
        if (definition == null) {
            return "redirect:/reconcile/units";
        }
        List<String> targets = targetsOf(row, rows);
        if (targets.isEmpty()) {
            redirect.addFlashAttribute("flashError", "묶을 줄을 하나도 고르지 않았습니다.");
            return back(definitionId, tab, q, page);
        }

        UUID tenantId = TenantContext.current();
        int linked = 0;
        UUID lastUnitId = null;
        String lastName = "";
        List<String> refused = new ArrayList<>();
        for (String key : targets) {
            MatchBoard.Row found = board.findRow(tenantId, Pairing.of(definition), key).orElse(null);
            if (found == null) {
                continue;
            }
            // 한쪽만 든 묶음은 합산이 「좌 120 · 우 0」 이 되어 대조가 매일 전량 차이를 올린다.
            // 여럿을 한꺼번에 이을 때 하나 때문에 전부 막으면 나머지가 안 이어지므로, 이것만
            // 빼고 잇되 «무엇을 뺐는지» 를 반드시 말한다.
            if (!found.bothSides()) {
                refused.add(found.displayName());
                continue;
            }
            try {
                var unit = unitService.link(picksOf(found), newCode(), nameOf(definition, found), "EA");
                lastUnitId = unit.getId();
                lastName = unit.getName();
                linked++;
            } catch (BusinessException e) {
                redirect.addFlashAttribute("flashError",
                        errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
                return back(definitionId, tab, q, page);
            }
        }

        if (linked > 0) {
            // 묶고 나면 그 줄이 이 갈래에서 «사라진다» — 「묶어 둔 것」 으로 옮겨 가기 때문이다.
            // 어디로 갔는지 말하지 않으면 사람은 방금 무슨 일이 일어났는지 모른다.
            redirect.addFlashAttribute("flashSuccess", linked == 1
                    ? "「%s」 로 묶었습니다. 이제 맞춰 볼 때 합산에 들어갑니다.".formatted(lastName)
                    : "%d개를 묶었습니다. 이제 맞춰 볼 때 합산에 들어갑니다.".formatted(linked));
            redirect.addFlashAttribute("flashLink", listUrl(definitionId, "LINKED"));
            redirect.addFlashAttribute("flashLinkLabel", "묶어 둔 것 보기");
            // 되돌리기는 «하나만 묶었을 때» 준다. 여럿을 한꺼번에 되돌리면 무엇이 풀렸는지
            // 알 수 없어 되돌리기가 또 다른 사고가 된다.
            if (linked == 1 && lastUnitId != null) {
                redirect.addFlashAttribute("flashUndo", undoUrl(lastUnitId, definitionId, tab));
            }
        }
        if (!refused.isEmpty()) {
            redirect.addFlashAttribute("flashError", "%s — %s".formatted(
                    String.join(", ", refused),
                    errorMessages.resolve(ErrorCode.RECONCILE_LINK_ONE_SIDED)));
        }
        return back(definitionId, tab, q, page);
    }

    /**
     * 줄 안에서 고른 반대쪽 품목과 <b>둘을 잇는다.</b>
     *
     * <p>이름이 서로 달라 자동 후보에 안 잡히는 품목이 여기로 풀린다. 이 길이 없으면 사람이
     * 「저 둘이 같은 거야」 라고 알고 있어도 손댈 자리가 없는 막다른 길이 된다.
     */
    @PostMapping("/reconcile/units/pair")
    public String pair(@RequestParam String rowKey,
                       @RequestParam String mate,
                       @RequestParam(required = false) UUID definitionId,
                       @RequestParam(required = false) String tab,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       RedirectAttributes redirect) {
        ReconcileDefinition definition = requireDefinition(definitionId);
        if (definition == null) {
            return "redirect:/reconcile/units";
        }
        UUID tenantId = TenantContext.current();
        MatchBoard.Row row = board.findRow(tenantId, Pairing.of(definition), rowKey).orElse(null);
        MatchBoard.Row mateRow = mateRowOf(tenantId, definition, mate);
        if (row == null || mateRow == null) {
            redirect.addFlashAttribute("flashError",
                    "고른 품목을 지금 자료에서 찾지 못했습니다. 화면을 새로 고친 뒤 다시 해 보세요.");
            return back(definitionId, tab, q, page);
        }

        List<ReconcileUnitService.Pick> picks = new ArrayList<>(picksOf(row));
        picks.addAll(picksOf(mateRow).stream()
                .filter(pick -> MatchBoard.tokenOf(pick.source(), pick.itemRef()).equals(mate))
                .toList());
        try {
            var unit = unitService.link(picks, newCode(), nameOf(definition, row), "EA");
            redirect.addFlashAttribute("flashSuccess",
                    "「%s」 로 묶었습니다. 이제 맞춰 볼 때 합산에 들어갑니다."
                            .formatted(unit.getName()));
            redirect.addFlashAttribute("flashUndo", undoUrl(unit.getId(), definitionId, tab));
            redirect.addFlashAttribute("flashLink", listUrl(definitionId, "LINKED"));
            redirect.addFlashAttribute("flashLinkLabel", "묶어 둔 것 보기");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
        }
        return back(definitionId, tab, q, page);
    }

    /** 고른 품목이 든 줄. 짝 후보는 줄이 아니라 품목이므로 그 품목이 속한 줄을 되찾는다. */
    private MatchBoard.Row mateRowOf(UUID tenantId, ReconcileDefinition definition, String token) {
        return board.findRowByItem(tenantId, Pairing.of(definition), token).orElse(null);
    }

    /**
     * 이 묶음을 푼다 — 든 품목이 「묶을 수 있는 것」 으로 돌아온다.
     *
     * <p>되돌릴 길이 없으면 잘못 이은 것이 영영 남는다. 한 품목은 한 묶음에만 속하므로 그
     * 품목을 다시 이을 수도 없다.
     */
    @PostMapping("/reconcile/units/{unitId}/unlink")
    public String unlink(@PathVariable UUID unitId,
                         @RequestParam(required = false) UUID definitionId,
                         @RequestParam(required = false) String tab,
                         @RequestParam(required = false) String q,
                         @RequestParam(defaultValue = "0") int page,
                         RedirectAttributes redirect) {
        unitService.unlinkUnit(unitId);
        redirect.addFlashAttribute("flashSuccess", "풀었습니다. 다시 이을 수 있습니다.");
        return back(definitionId, tab, q, page);
    }

    /** 예전 경로로 만들어져 확인을 기다리는 묶음을 확정한다. */
    @PostMapping("/reconcile/units/{unitId}/confirm")
    public String confirm(@PathVariable UUID unitId,
                          @RequestParam(required = false) UUID definitionId,
                          @RequestParam(required = false) String tab,
                          @RequestParam(required = false) String q,
                          @RequestParam(defaultValue = "0") int page,
                          RedirectAttributes redirect) {
        // 묶음 «통째로» 확정한다. 한쪽만 확정하면 합산이 반쪽이 되어 대조가 매일 유령 차이를
        // 올리고, 사람은 그것을 매칭 문제가 아니라 재고 사고로 읽는다.
        unitService.confirmUnit(unitId);
        redirect.addFlashAttribute("flashSuccess", "확인했습니다. 이제 대조에 들어갑니다.");
        return back(definitionId, tab, q, page);
    }

    /**
     * 「이건 짝이 없다」 — 고른 줄의 품목들을 할 일에서 뺀다.
     *
     * <p>이 길이 없으면 할 일 개수가 <b>영영 0이 되지 않는다.</b> 한쪽에만 있는 품목은 늘
     * 남기 때문이다. 도달하지 못하는 숫자는 사람이 곧 안 보게 된다.
     */
    @PostMapping("/reconcile/units/set-aside")
    public String setAside(@RequestParam(required = false) List<String> rows,
                           @RequestParam(required = false) String row,
                           @RequestParam(defaultValue = "NO_COUNTERPART") String reason,
                           @RequestParam(required = false) UUID definitionId,
                           @RequestParam(required = false) String tab,
                           @RequestParam(required = false) String q,
                           @RequestParam(defaultValue = "0") int page,
                           RedirectAttributes redirect) {
        ReconcileDefinition definition = requireDefinition(definitionId);
        if (definition == null) {
            return "redirect:/reconcile/units";
        }
        List<String> targets = targetsOf(row, rows);
        if (targets.isEmpty()) {
            redirect.addFlashAttribute("flashError", "줄을 하나도 고르지 않았습니다.");
            return back(definitionId, tab, q, page);
        }

        UUID tenantId = TenantContext.current();
        UnpairedItem.Reason parsed = reasonOf(reason);
        int marked = 0;
        for (String key : targets) {
            MatchBoard.Row found = board.findRow(tenantId, Pairing.of(definition), key).orElse(null);
            if (found == null || found.kind() == MatchBoard.Kind.LINKED) {
                continue;
            }
            for (MatchBoard.Item item : found.items()) {
                unpairedService.setAside(item.source(), item.itemRef(), parsed, "");
                marked++;
            }
        }
        redirect.addFlashAttribute("flashSuccess",
                "%d개 품목을 짝 없음으로 두었습니다.".formatted(marked));
        redirect.addFlashAttribute("flashLink",
                "/reconcile/units?definitionId=" + definitionId + "&tab=SET_ASIDE#board");
        redirect.addFlashAttribute("flashLinkLabel", "짝 없음으로 둔 것 보기");
        return back(definitionId, tab, q, page);
    }

    /** 짝 없음 표시를 뗀다. 단종인 줄 알았는데 다시 들어오는 일이 실제로 있다. */
    @PostMapping("/reconcile/units/restore")
    public String restore(@RequestParam(required = false) List<String> rows,
                          @RequestParam(required = false) String row,
                          @RequestParam(required = false) UUID definitionId,
                          @RequestParam(required = false) String tab,
                          @RequestParam(required = false) String q,
                          @RequestParam(defaultValue = "0") int page,
                          RedirectAttributes redirect) {
        ReconcileDefinition definition = requireDefinition(definitionId);
        if (definition == null) {
            return "redirect:/reconcile/units";
        }
        UUID tenantId = TenantContext.current();
        int restored = 0;
        for (String key : targetsOf(row, rows)) {
            MatchBoard.Row found = board.findRow(tenantId, Pairing.of(definition), key).orElse(null);
            if (found == null) {
                continue;
            }
            for (MatchBoard.Item item : found.items()) {
                unpairedService.restore(item.source(), item.itemRef());
                restored++;
            }
        }
        redirect.addFlashAttribute("flashSuccess",
                "%d개 품목을 할 일로 되돌렸습니다.".formatted(restored));
        return back(definitionId, tab, q, page);
    }

    /**
     * 묶음 이름을 고친다.
     *
     * <p>이름은 <b>대조 결과에서 사람이 잡을 수 있는 유일한 손잡이</b>다. 「U-6668d23b · +11」
     * 이라고만 뜨면 그것이 무슨 묶음인지 알 수 없고, 알 수 없는 줄은 손대지 않게 된다.
     */
    @PostMapping("/reconcile/units/{unitId}/rename")
    public String rename(@PathVariable UUID unitId,
                         @RequestParam String name,
                         @RequestParam(required = false) UUID definitionId,
                         @RequestParam(required = false) String tab,
                         @RequestParam(required = false) String q,
                         @RequestParam(defaultValue = "0") int page,
                         RedirectAttributes redirect) {
        try {
            var unit = unitService.rename(unitId, name);
            redirect.addFlashAttribute("flashSuccess",
                    "「%s」 로 이름을 바꿨습니다.".formatted(unit.getName()));
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
        }
        return back(definitionId, tab, q, page);
    }

    /**
     * 담긴 품명으로 이름을 <b>다시 짓는다</b> — 고른 것만.
     *
     * <p>이름 규칙을 고쳐도 이미 만들어진 묶음은 옛 이름을 그대로 달고 있다. 로트 날짜가 박힌
     * 이름이 열 개 넘게 남아 있는데 하나씩 손으로 고치라고 하면 아무도 안 고친다 — 그러면
     * 규칙을 고친 의미가 없다.
     *
     * <p>그래도 <b>일괄로 덮지 않고 고른 것만</b> 바꾼다. 사람이 직접 지은 이름을 코드가
     * 말없이 되돌리면, 다음부터는 이름을 짓지 않게 된다.
     */
    @PostMapping("/reconcile/units/rename-suggested")
    public String renameSuggested(@RequestParam(required = false) List<String> units,
                                  @RequestParam(required = false) UUID definitionId,
                                  @RequestParam(required = false) String tab,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "0") int page,
                                  RedirectAttributes redirect) {
        ReconcileDefinition definition = requireDefinition(definitionId);
        if (definition == null) {
            return "redirect:/reconcile/units";
        }
        if (units == null || units.isEmpty()) {
            redirect.addFlashAttribute("flashError", "다시 지을 묶음을 고르지 않았습니다.");
            return back(definitionId, tab, q, page);
        }

        UUID tenantId = TenantContext.current();
        int renamed = 0;
        for (String raw : units) {
            UUID unitId;
            try {
                unitId = UUID.fromString(raw);
            } catch (IllegalArgumentException e) {
                continue;
            }
            String suggested = naming.suggest(tenantId, unitId,
                    definition.getLeftSource(), definition.getRightSource());
            if (suggested.isBlank()) {
                continue;
            }
            unitService.rename(unitId, suggested);
            renamed++;
        }
        redirect.addFlashAttribute("flashSuccess",
                "%d개 이름을 담긴 품명으로 다시 지었습니다.".formatted(renamed));
        return back(definitionId, tab, q, page);
    }

    /**
     * 이 품목 하나가 묶음 몇 개인지 고친다.
     *
     * <p>「전산의 1박스 = 물류의 12개」 같은 경우다. 잘못 넣으면 수량이 통째로 어긋나므로
     * 고칠 길이 있어야 한다. 자주 쓰는 값이 아니라 이어 둔 줄을 펼쳤을 때만 보인다.
     */
    @PostMapping("/reconcile/units/members/{memberId}/factor")
    public String changeFactor(@PathVariable UUID memberId,
                               @RequestParam BigDecimal factor,
                               @RequestParam(required = false) UUID definitionId,
                               @RequestParam(required = false) String tab,
                               @RequestParam(required = false) String q,
                               @RequestParam(defaultValue = "0") int page,
                               RedirectAttributes redirect) {
        try {
            unitService.changeFactor(memberId, factor);
            redirect.addFlashAttribute("flashSuccess", "몇 개로 칠지 바꿨습니다.");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashError",
                    errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
        }
        return back(definitionId, tab, q, page);
    }

    // ── 거들기 ──────────────────────────────────────────────────────────────

    /**
     * 이번 동작이 다룰 줄들.
     *
     * <p>줄 하나짜리 버튼({@code row})과 여러 줄 고르기({@code rows})가 <b>같은 화면에</b>
     * 있다. 체크상자는 표 밖의 폼을 가리키므로 줄 버튼을 눌러도 함께 실려 온다 — 줄 버튼을
     * 눌렀는데 체크해 둔 것까지 이어지면 사람이 하지 않은 일이 일어난다.
     */
    private List<String> targetsOf(String single, List<String> selected) {
        if (single != null && !single.isBlank()) {
            return List.of(single);
        }
        return selected == null ? List.of() : selected;
    }

    /** 방금 만든 묶음을 그 자리에서 풀 수 있는 길. */
    private String undoUrl(UUID unitId, UUID definitionId, String tab) {
        return UriComponentsBuilder.fromPath("/reconcile/units/{unitId}/unlink")
                .queryParam("definitionId", definitionId)
                .queryParamIfPresent("tab", java.util.Optional.ofNullable(
                        tab == null || tab.isBlank() ? null : tab))
                .buildAndExpand(unitId).encode().toUriString();
    }

    /** 방금 한 것이 «어느 갈래로 갔는지» 보러 가는 길. */
    private String listUrl(UUID definitionId, String tab) {
        return UriComponentsBuilder.fromPath("/reconcile/units")
                .queryParam("definitionId", definitionId)
                .queryParam("tab", tab)
                .fragment("board")
                .build().encode().toUriString();
    }

    private List<ReconcileUnitService.Pick> picksOf(MatchBoard.Row row) {
        return row.items().stream()
                .map(item -> new ReconcileUnitService.Pick(item.source(), item.itemRef(),
                        item.factor()))
                .toList();
    }

    /**
     * 이 대조에 정해 둔 규칙으로 묶음 이름을 짓는다.
     *
     * <p>규칙이 「직접」 이면 코드가 짓지 않고 「이름 없음」 으로 둔다 — 목록에서 눈에 띄므로
     * 사람이 반드시 짓게 된다. 그럴듯한 이름을 지어 두면 아무도 안 고친다.
     */
    private String nameOf(ReconcileDefinition definition, MatchBoard.Row row) {
        String name = UnitNameRule.of(definition.getUnitNameRule()).nameOf(
                row.left().stream().map(MatchBoard.Item::displayName).toList(),
                row.right().stream().map(MatchBoard.Item::displayName).toList());
        return name.isBlank() ? "이름 없음" : name;
    }

    /** 코드는 사람에게 묻지 않는다 — 사람이 신경 쓸 값이 아니고, 겹치면 저장이 막힌다. */
    private String newCode() {
        return "U-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UnpairedItem.Reason reasonOf(String raw) {
        try {
            return UnpairedItem.Reason.valueOf(raw);
        } catch (IllegalArgumentException e) {
            // 주소를 손으로 고쳐 이상한 값이 와도 화면이 깨지지 않는다.
            return UnpairedItem.Reason.NO_COUNTERPART;
        }
    }

    private ReconcileDefinition requireDefinition(UUID definitionId) {
        List<ReconcileDefinition> active = definitions.findByIsActiveTrueOrderByCode();
        return pick(definitionId, active);
    }

    /**
     * 보던 자리로 <b>그대로</b> 돌아간다.
     *
     * <p>탭·검색어·쪽을 안 들고 가면 한 건 처리할 때마다 첫 화면으로 튕긴다. 이을 것이 스무
     * 개면 스무 번 처음부터 찾아 들어가야 한다.
     *
     * <p>{@code #board} 는 표 자리를 가리킨다. 없으면 매번 화면 맨 위로 올라가 방금 무엇을
     * 눌렀는지 놓친다.
     */
    private String back(UUID definitionId, String tab, String q, int page) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath("/reconcile/units");
        if (definitionId != null) {
            url.queryParam("definitionId", definitionId);
        }
        if (tab != null && !tab.isBlank()) {
            url.queryParam("tab", tab);
        }
        if (q != null && !q.isBlank()) {
            url.queryParam("q", q);
        }
        if (page > 0) {
            url.queryParam("page", page);
        }
        return "redirect:" + url.fragment("board").build().encode().toUriString();
    }
}
