package kr.suhsaechan.palim.web.reconcile;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.match.MatchBoard;
import kr.suhsaechan.palim.reconcile.match.UnitBreakdown;
import kr.suhsaechan.palim.reconcile.match.UnitNameRule;
import kr.suhsaechan.palim.reconcile.rule.NormalizationEngine;
import kr.suhsaechan.palim.reconcile.rule.NormalizationPreview;
import kr.suhsaechan.palim.reconcile.rule.NormalizationRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 「이 화면이 무슨 일을 하나」 와 「맞추는 방식」.
 *
 * <p>두 화면 다 <b>없어서 생긴 문제</b>를 메운다.
 *
 * <p>안내가 없어서 화면은 「이걸 하면 무엇이 어떻게 되는지」 를 한 번도 말하지 않았다. 그래서
 * 버튼 이름만 늘어날 뿐 사람은 무엇을 하는 중인지 몰랐고, 「묶기」·「1↔2건」 이 전부 뜻 없는
 * 글자로 보였다.
 *
 * <p>조절 지점은 세 군데에 흩어져 있었다 — 이름 다듬기 규칙은 별도 화면, 뜯어보기 기준은 맞춰
 * 본 결과 안쪽, 몇 개로 칠지는 품목 줄 안쪽. <b>무엇을 조절할 수 있는지 보여주는 자리가
 * 없었으므로</b> 자기 자료에 안 맞아도 그냥 참고 썼다(07-DECISIONS 041).
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class MethodController {

    private final ReconcileDefinitionRepository definitions;
    private final UnitBreakdown breakdowns;
    private final MatchBoard board;
    private final NormalizationRuleRepository rules;
    private final NormalizationEngine normalizer;
    private final NormalizationPreview preview;
    private final JdbcClient jdbcClient;

    /**
     * 이 화면이 무슨 일을 하나.
     *
     * <p>지어낸 예시를 쓰지 않는다 — 「그런가 보다」 로 끝난다. <b>자기 화면에 실제로 있는 줄</b>이
     * 나와야 「아 저게 그거구나」 로 이어진다.
     */
    @GetMapping("/reconcile/guide")
    public String guide(Model model) {
        model.addAttribute("title", "이 화면이 무슨 일을 하나");
        ReconcileDefinition definition = firstDefinition();
        model.addAttribute("definition", definition);
        if (definition != null) {
            model.addAttribute("sample", sampleRow(definition));
        }
        return "reconcile/guide";
    }

    /**
     * 가장 <b>설명이 되는</b> 줄 하나 — 양쪽이 여러 품목으로 쪼개진 것.
     *
     * <p>1↔1 짜리를 보여주면 「그냥 코드끼리 맞추면 되잖아」 로 읽혀서 설명이 안 된다. 이 화면이
     * 왜 필요한지는 <b>한쪽이 여러 줄로 쪼개져 있을 때</b> 비로소 드러난다.
     */
    private MatchBoard.Row sampleRow(ReconcileDefinition definition) {
        List<MatchBoard.Row> rows = board.load(TenantContext.current(),
                definition.getLeftSource(), definition.getRightSource(),
                MatchBoard.Tab.ALL, null, 0).rows();
        return rows.stream()
                .filter(row -> row.left().size() > 1 || row.right().size() > 1)
                .findFirst()
                .or(() -> rows.stream().filter(MatchBoard.Row::bothSides).findFirst())
                .orElse(null);
    }

    /** 조절할 수 있는 것들을 한 자리에. */
    @GetMapping("/reconcile/method")
    public String method(Model model) {
        model.addAttribute("title", "맞추는 방식");
        UUID tenantId = TenantContext.current();
        ReconcileDefinition definition = firstDefinition();
        model.addAttribute("definition", definition);
        model.addAttribute("nameRules", UnitNameRule.values());
        model.addAttribute("activeRules",
                rules.findByIsActiveTrueOrderBySortOrder().size());

        // 규칙이 «실제로 무엇을 하는지» 를 한 줄로 보여준다. 개수만 적으면 눌러 봐야 안다.
        List<String> samples = preview.sampleNames();
        if (!samples.isEmpty()) {
            model.addAttribute("sampleBefore", samples.getFirst());
            model.addAttribute("sampleAfter", normalizer.normalize(samples.getFirst()));
        }

        if (definition != null) {
            model.addAttribute("axes", breakdowns.axes(tenantId));
            model.addAttribute("scaledCount", scaledMembers(tenantId));
        }
        return "reconcile/method";
    }

    /** 몇 개로 칠지를 1이 아닌 값으로 정해 둔 품목 수. */
    private int scaledMembers(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT count(*)::int FROM reconcile_unit_member
                         WHERE tenant_id = :tenantId AND factor <> 1
                        """)
                .param("tenantId", tenantId)
                .query(Integer.class)
                .single();
    }

    @PostMapping("/reconcile/{id}/unit-name-rule")
    public String changeUnitNameRule(@PathVariable UUID id,
                                     @RequestParam String rule,
                                     RedirectAttributes redirect) {
        ReconcileDefinition definition = definitions.findById(id).orElseThrow();
        UnitNameRule using = UnitNameRule.of(rule);
        definition.changeUnitNameRule(using.name());
        definitions.save(definition);
        redirect.addFlashAttribute("flashSuccess",
                "앞으로 묶음 이름을 「%s」 로 짓습니다.".formatted(using.getLabel()));
        return "redirect:/reconcile/method";
    }

    /**
     * 지금 보고 있는 대조.
     *
     * <p>여럿이면 코드순 첫 번째를 쓴다. 이 화면은 <b>고르는 자리가 아니라 보여주는 자리</b>라
     * 여기서 또 묻게 하면 설명을 보러 온 사람이 질문부터 받는다.
     */
    private ReconcileDefinition firstDefinition() {
        return definitions.findByIsActiveTrueOrderByCode().stream().findFirst().orElse(null);
    }
}
