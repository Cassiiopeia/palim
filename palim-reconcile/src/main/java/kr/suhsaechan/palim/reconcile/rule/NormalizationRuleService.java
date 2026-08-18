package kr.suhsaechan.palim.reconcile.rule;

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
        preview.validate(pattern);
        int next = rules.findAllByOrderBySortOrder().stream()
                .mapToInt(NormalizationRule::getSortOrder)
                .max()
                .orElse(0) + ORDER_STEP;
        NormalizationRule saved = rules.save(NormalizationRule.of(
                TenantContext.current(), name, pattern, replacement, next));
        engine.clearCache();
        log.info("이름 다듬기 규칙 추가 — {}", name);
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
