package kr.suhsaechan.palim.reconcile.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이름 다듬기 규칙을 만들고 고친다.
 *
 * <p>규칙이 <b>DB 에만 있고 화면이 없으면</b> 이 프로그램은 한 회사에서만 돈다. 표기 습관은
 * 회사마다 다른데 — 어디는 「/」 로 규격을 나누고 어디는 「(2026-10-17)」 로 유통기한을 붙인다 —
 * 그것을 넣을 자리가 없으면 다른 곳에 가져다 놓는 순간 「묶을 만한 것」 이 늘 비어 있게 된다.
 *
 * <p><b>고치면 캐시를 비운다.</b> 엔진이 컴파일한 정규식을 들고 있으므로, 안 비우면 고친 뒤에도
 * 옛 패턴으로 계속 돈다 — 화면은 저장됐다고 말하는데 결과가 안 바뀐다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NormalizationRuleService {

    /** 새 규칙을 목록 끝에 두기 위한 간격. 사이에 끼워 넣을 자리를 남긴다. */
    private static final int ORDER_STEP = 10;

    private final NormalizationRuleRepository rules;
    private final NormalizationEngine engine;
    private final NormalizationPreview preview;

    @Transactional(readOnly = true)
    public List<NormalizationRule> all() {
        return rules.findAllByOrderBySortOrder();
    }

    @Transactional
    public NormalizationRule create(String name, String pattern, String replacement) {
        return create(name, pattern, replacement, null);
    }

    /**
     * @param sourceCode 이 규칙을 걸 원천. 비면 모든 원천에 건다
     */
    @Transactional
    public NormalizationRule create(String name, String pattern, String replacement,
                                    String sourceCode) {
        preview.validate(pattern);
        int next = rules.findAllByOrderBySortOrder().stream()
                .mapToInt(NormalizationRule::getSortOrder)
                .max()
                .orElse(0) + ORDER_STEP;
        NormalizationRule saved = rules.save(NormalizationRule.of(
                TenantContext.current(), name, pattern, replacement, next, sourceCode));
        engine.clearCache();
        log.info("이름 다듬기 규칙 추가 — {} (원천 {})", name,
                sourceCode == null ? "전체" : sourceCode);
        return saved;
    }

    @Transactional
    public NormalizationRule update(UUID id, String name, String pattern, String replacement) {
        preview.validate(pattern);
        NormalizationRule rule = require(id);
        rule.update(name, pattern, replacement, rule.getSortOrder());
        NormalizationRule saved = rules.save(rule);
        engine.clearCache();
        return saved;
    }

    @Transactional
    public NormalizationRule update(UUID id, String name, String pattern, String replacement,
                                    String sourceCode) {
        preview.validate(pattern);
        NormalizationRule rule = require(id);
        rule.update(name, pattern, replacement, rule.getSortOrder(), sourceCode);
        NormalizationRule saved = rules.save(rule);
        engine.clearCache();
        return saved;
    }

    /**
     * 끌어서 옮긴 순서를 그대로 저장한다.
     *
     * <p>한 칸씩 옮기는 {@link #move} 는 규칙이 다섯 개만 되어도 「맨 아래를 맨 위로」에 네 번을
     * 눌러야 하고, 누를 때마다 화면이 새로 그려져 어디까지 옮겼는지 놓친다.
     *
     * <p><b>넘어온 목록에 없는 규칙은 지우지 않는다.</b> 화면을 열어둔 사이에 다른 사람이 규칙을
     * 넣었을 수 있고, 그것이 순서 저장 한 번으로 사라지면 원인을 찾을 방법이 없다. 목록에 없는
     * 것은 뒤로 붙인다.
     */
    @Transactional
    public void reorder(List<UUID> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            return;
        }
        List<NormalizationRule> current = rules.findAllByOrderBySortOrder();
        // 화면이 보낸 순서를 먼저 깔고, 그 사이에 생긴 규칙을 뒤에 붙인다.
        List<NormalizationRule> ordered = new ArrayList<>();
        for (UUID id : orderedIds) {
            current.stream()
                    .filter(rule -> rule.getId().equals(id))
                    .findFirst()
                    .ifPresent(ordered::add);
        }
        current.stream().filter(rule -> !ordered.contains(rule)).forEach(ordered::add);

        int order = ORDER_STEP;
        for (NormalizationRule rule : ordered) {
            rule.moveTo(order);
            order += ORDER_STEP;
        }
        rules.saveAll(ordered);
        engine.clearCache();
        log.info("이름 다듬기 규칙 순서 변경 — {}건", ordered.size());
    }

    /** 규칙을 걸 수 있는 원천 목록. 지금 담긴 재고에 실제로 들어 있는 것만 보여준다. */
    @Transactional(readOnly = true)
    public List<String> availableSources() {
        return preview.sources();
    }

    /**
     * 켜고 끈다.
     *
     * <p>지우는 대신 끄는 길을 먼저 둔다. 규칙 하나를 껐다 켜 보면서 「묶을 만한 것」 개수가
     * 어떻게 변하는지 보는 것이 이 화면에서 제일 자주 하는 일이다.
     */
    @Transactional
    public NormalizationRule toggle(UUID id) {
        NormalizationRule rule = require(id);
        if (rule.isActive()) {
            rule.deactivate();
        } else {
            rule.activate();
        }
        NormalizationRule saved = rules.save(rule);
        engine.clearCache();
        return saved;
    }

    /**
     * 켜져 있는 규칙을 <b>한 번에</b> 끈다.
     *
     * <p>하나씩 끄면 규칙이 넷일 때 네 번, 스물이면 스무 번이다. 그런데 이 화면에서 제일 자주
     * 하는 일이 <b>껐다 켜 보며 짝 개수가 어떻게 변하는지</b> 보는 것이다 — 그 왕복이 길면
     * 아무도 하지 않고, 결국 규칙이 무슨 일을 하는지 모르는 채로 쌓인다.
     *
     * <p><b>지우지 않고 끈다.</b> 지운 규칙의 정규식은 기억에서 복원되지 않는다. 끄면 목록에
     * 남아 있어 언제든 되돌릴 수 있고, 무엇을 껐는지도 보인다.
     *
     * @return 실제로 끈 규칙 수. 이미 다 꺼져 있었으면 0
     */
    @Transactional
    public int deactivateAll() {
        List<NormalizationRule> on = rules.findAllByOrderBySortOrder().stream()
                .filter(NormalizationRule::isActive)
                .toList();
        if (on.isEmpty()) {
            return 0;
        }
        on.forEach(NormalizationRule::deactivate);
        rules.saveAll(on);
        engine.clearCache();
        log.info("이름 다듬기 규칙 일괄 끄기 — {}건", on.size());
        return on.size();
    }

    /**
     * 순서를 한 칸 옮긴다.
     *
     * <p>순서가 결과를 바꾼다 — 괄호를 떼기 전에 공백을 지우면 괄호 규칙이 안 맞는다. 그래서
     * 순서를 사람이 만질 수 있어야 한다.
     */
    @Transactional
    public void move(UUID id, boolean up) {
        List<NormalizationRule> ordered = rules.findAllByOrderBySortOrder();
        int at = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(id)) {
                at = i;
                break;
            }
        }
        int other = up ? at - 1 : at + 1;
        if (at < 0 || other < 0 || other >= ordered.size()) {
            // 끝에서 더 밀었다. 오류가 아니라 할 일이 없는 것이다.
            return;
        }
        NormalizationRule moving = ordered.get(at);
        NormalizationRule neighbour = ordered.get(other);
        int movingOrder = moving.getSortOrder();
        int neighbourOrder = neighbour.getSortOrder();
        // 두 규칙의 순서 값이 같으면 자리를 바꿔도 목록이 안 변한다. 그때는 벌려 준다.
        if (movingOrder == neighbourOrder) {
            neighbourOrder = up ? movingOrder - 1 : movingOrder + 1;
        }
        moving.update(moving.getName(), moving.getPattern(), moving.getReplacement(),
                neighbourOrder);
        neighbour.update(neighbour.getName(), neighbour.getPattern(),
                neighbour.getReplacement(), movingOrder);
        rules.save(moving);
        rules.save(neighbour);
        engine.clearCache();
    }

    @Transactional
    public void delete(UUID id) {
        rules.delete(require(id));
        engine.clearCache();
    }

    private NormalizationRule require(UUID id) {
        return rules.findById(id).orElseThrow(() ->
                new BusinessException(ErrorCode.INVALID_INPUT, "없는 규칙입니다."));
    }
}
