package kr.suhsaechan.palim.reconcile.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.run.DiffState;
import kr.suhsaechan.palim.reconcile.run.DiffType;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiff;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiffRepository;
import kr.suhsaechan.palim.reconcile.run.ReconcileRun;
import kr.suhsaechan.palim.reconcile.run.ReconcileRunRepository;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnit;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 두 원천의 재고를 견주어 차이를 남긴다.
 *
 * <p>이 클래스는 <b>비교 규칙만</b> 안다. 합산은 {@link SnapshotAggregator}, 기준 시각 확인은
 * {@link BaseAtResolver}, 승격 판정은 {@link DiffPromoter} 가 맡는다.
 *
 * <p>허용 오차 이내는 <b>기록하지 않는다.</b> 소수점 반올림이나 낱개 한두 개까지 전부 띄우면
 * 목록이 잡음으로 차서 진짜 문제가 묻힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileEngine {

    private final ReconcileDefinitionRepository definitions;
    private final ReconcileUnitRepository units;
    private final ReconcileRunRepository runs;
    private final ReconcileDiffRepository diffs;
    private final SnapshotAggregator aggregator;
    private final BaseAtResolver baseAtResolver;
    private final DiffPromoter promoter;

    /**
     * 대조 한 번.
     *
     * <p>기준 시각이 어긋나면 실행을 <b>실패로 남기고</b> 끝낸다. 조용히 넘어가면 며칠째 대조가
     * 안 되고 있다는 사실을 아무도 모른다.
     */
    @Transactional
    public ReconcileRun run(UUID definitionId) {
        ReconcileDefinition definition = definitions.findById(definitionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT,
                        "없는 대조 정의입니다."));
        UUID tenantId = TenantContext.current();

        Instant baseAt;
        try {
            baseAt = baseAtResolver.resolve(tenantId,
                    definition.getLeftSource(), definition.getRightSource());
        } catch (BusinessException e) {
            // 실패도 기록이다. 「어제는 왜 안 돌았나」 에 답할 수 있어야 사람이 고친다.
            ReconcileRun failed = runs.save(
                    ReconcileRun.start(tenantId, definitionId, Instant.EPOCH));
            failed.fail(e.getMessage());
            return runs.save(failed);
        }

        ReconcileRun run = runs.save(ReconcileRun.start(tenantId, definitionId, baseAt));

        Map<UUID, BigDecimal> left = aggregator.sumByUnit(
                tenantId, definition.getLeftSource(), baseAt, definition.getCompareField());
        Map<UUID, BigDecimal> right = aggregator.sumByUnit(
                tenantId, definition.getRightSource(), baseAt, definition.getCompareField());

        List<ReconcileDiff> found = new ArrayList<>();
        found.addAll(compareUnits(definition, run, left, right));
        int unmatched = recordUnmatched(definition, run, baseAt, found);

        diffs.saveAll(found);
        run.succeed(left.size(), right.size(), found.size() - unmatched, unmatched);
        return runs.save(run);
    }

    /** 양쪽에 있는 단위를 견준다. 한쪽에만 있으면 그쪽 수량과 0 을 비교한 것이 된다. */
    private List<ReconcileDiff> compareUnits(ReconcileDefinition definition, ReconcileRun run,
                                             Map<UUID, BigDecimal> left,
                                             Map<UUID, BigDecimal> right) {
        Set<UUID> allUnits = new LinkedHashSet<>();
        allUnits.addAll(left.keySet());
        allUnits.addAll(right.keySet());

        List<ReconcileDiff> found = new ArrayList<>();
        for (UUID unitId : allUnits) {
            BigDecimal leftQty = left.getOrDefault(unitId, BigDecimal.ZERO);
            BigDecimal rightQty = right.getOrDefault(unitId, BigDecimal.ZERO);
            BigDecimal delta = leftQty.subtract(rightQty);

            // 허용 오차 이내는 차이로 보지 않는다. 잡음이 쌓이면 진짜 문제가 묻힌다.
            if (delta.abs().compareTo(definition.getTolerance()) <= 0) {
                continue;
            }

            DiffType type = delta.signum() > 0 ? DiffType.LEFT_MORE : DiffType.RIGHT_MORE;
            var promotion = promoter.decide(definition.getId(), run.getId(), unitId, type);

            found.add(ReconcileDiff.of(run.getTenantId(), run.getId(), unitId,
                    unitCodeOf(unitId), leftQty, rightQty, delta, type,
                    promotion.state(), promotion.firstSeenRunId()));
        }
        return found;
    }

    /**
     * 어느 단위에도 속하지 않은 품목을 남긴다.
     *
     * <p><b>미매칭은 실패가 아니라 결과의 한 유형이다.</b> 이것 때문에 대조 전체를 중단하면
     * 나머지 결과도 못 보게 되고, 사람이 매칭을 끝낼 때까지 대조를 아예 쓸 수 없다.
     */
    private int recordUnmatched(ReconcileDefinition definition, ReconcileRun run, Instant baseAt,
                                List<ReconcileDiff> found) {
        int count = 0;
        count += addUnmatched(run, definition, baseAt, definition.getLeftSource(),
                DiffType.UNMATCHED_LEFT, found);
        count += addUnmatched(run, definition, baseAt, definition.getRightSource(),
                DiffType.UNMATCHED_RIGHT, found);
        return count;
    }

    private int addUnmatched(ReconcileRun run, ReconcileDefinition definition, Instant baseAt,
                             String source, DiffType type, List<ReconcileDiff> found) {
        var items = aggregator.unmatched(run.getTenantId(), source, baseAt,
                definition.getCompareField());

        for (var item : items) {
            boolean isLeft = type == DiffType.UNMATCHED_LEFT;
            found.add(ReconcileDiff.of(run.getTenantId(), run.getId(), null,
                    // 단위가 없으므로 사람이 알아볼 이름을 대신 남긴다.
                    item.rawName().isBlank() ? item.itemRef() : item.rawName(),
                    isLeft ? item.quantity() : BigDecimal.ZERO,
                    isLeft ? BigDecimal.ZERO : item.quantity(),
                    isLeft ? item.quantity() : item.quantity().negate(),
                    type, DiffState.OBSERVING, run.getId()));
        }
        return items.size();
    }

    private String unitCodeOf(UUID unitId) {
        return units.findById(unitId).map(ReconcileUnit::getCode).orElse("");
    }
}
