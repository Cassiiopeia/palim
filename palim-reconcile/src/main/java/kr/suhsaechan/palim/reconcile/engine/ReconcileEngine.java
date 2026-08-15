package kr.suhsaechan.palim.reconcile.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
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
    /** 실패 사유를 사람 말로 옮긴다. 로그용 문자열이 화면에 그대로 나가면 안 된다. */
    private final ErrorMessageResolver errorMessages;

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

        log.debug("대조 시작 — 정의={}({}) 테넌트={} 좌원천={} 우원천={} 비교칸={} 허용오차={}",
                definitionId, definition.getCode(), tenantId,
                definition.getLeftSource(), definition.getRightSource(),
                definition.getCompareField(), definition.getTolerance());

        BaseAtResolver.Aligned aligned;
        try {
            aligned = baseAtResolver.resolve(tenantId,
                    definition.getLeftSource(), definition.getRightSource(),
                    definition.granularityOrDay());
        } catch (BusinessException e) {
            // 거부 사유는 «양쪽 시각» 이 함께 있어야 읽힌다. 한쪽만 남기면 왜 어긋났는지 모른다.
            log.error("기준 시각 확인 실패 — 대조를 거부한다. 정의={}({}) 좌원천={} 우원천={} "
                            + "사유={} 값={}",
                    definitionId, definition.getCode(),
                    definition.getLeftSource(), definition.getRightSource(),
                    e.getErrorCode(), Arrays.toString(e.messageArgs()), e);
            // 실패도 기록이다. 「어제는 왜 안 돌았나」 에 답할 수 있어야 사람이 고친다.
            //
            // getMessage() 를 그대로 담지 않는다. 그것은 「RECONCILE_SNAPSHOT_MISSING(R002)
            // args=[ecount-stock]」 같은 로그용 문자열이라, 화면에 그대로 나가면 사람은
            // 무엇을 해야 하는지 알 수 없다 — 실제로 그 화면을 보고 원인을 못 찾아 서버
            // 로그를 뒤져야 했다. 연동 화면은 이 규칙을 지키고 있었는데 대조 화면만 빠져 있었다.
            ReconcileRun failed = runs.save(
                    ReconcileRun.start(tenantId, definitionId, Instant.EPOCH));
            failed.fail(errorMessages.resolve(e.getErrorCode(), e.messageArgs()));
            return runs.save(failed);
        }

        // 실행에 남는 것은 «칸» 이다. 두 원천이 몇 시에 뽑혔든 「이 칸의 대조」 로 읽혀야
        // 이력이 한 줄로 이어진다. 합산은 각 원천이 실제로 가진 시각으로 한다 — 칸 시작
        // 시각에는 자료가 없을 수 있다.
        Instant baseAt = aligned.bucket();
        ReconcileRun run = runs.save(ReconcileRun.start(tenantId, definitionId, baseAt));
        log.debug("실행 생성 — 실행={} 정의={}({}) 기준시각={}",
                run.getId(), definitionId, definition.getCode(), baseAt);

        Map<UUID, BigDecimal> left = aggregator.sumByUnit(
                tenantId, definition.getLeftSource(), aligned.left(), definition.getCompareField());
        Map<UUID, BigDecimal> right = aggregator.sumByUnit(
                tenantId, definition.getRightSource(), aligned.right(),
                definition.getCompareField());
        log.debug("단위 합산 — 실행={} 칸={} 좌원천={}({}) {}단위 우원천={}({}) {}단위",
                run.getId(), baseAt,
                definition.getLeftSource(), aligned.left(), left.size(),
                definition.getRightSource(), aligned.right(), right.size());

        if (left.isEmpty() && right.isEmpty()) {
            log.warn("양쪽 합산 결과가 비어 있다 — 확정된 정합 단위가 없거나 해당 기준 시각의 "
                            + "스냅샷이 없다. 실행={} 정의={}({}) 기준시각={}",
                    run.getId(), definitionId, definition.getCode(), baseAt);
        }

        List<ReconcileDiff> found = new ArrayList<>();
        found.addAll(compareUnits(definition, run, left, right));
        int unmatched = recordUnmatched(definition, run, aligned, found);

        diffs.saveAll(found);
        run.succeed(left.size(), right.size(), found.size() - unmatched, unmatched);
        log.info("대조 완료 — 실행={} 정의={}({}) 기준시각={} 좌단위={}건 우단위={}건 "
                        + "차이={}건(확정={} 관찰중={} 무시={}) 미매칭={}건",
                run.getId(), definitionId, definition.getCode(), baseAt,
                left.size(), right.size(), found.size() - unmatched,
                countState(found, DiffState.CONFIRMED),
                countState(found, DiffState.OBSERVING),
                countState(found, DiffState.IGNORED),
                unmatched);
        return runs.save(run);
    }

    /** 상태별 차이 건수. 미매칭(단위 없음)은 따로 세므로 단위가 붙은 차이만 센다. */
    private static long countState(List<ReconcileDiff> found, DiffState state) {
        return found.stream()
                .filter(diff -> diff.getUnitId() != null && diff.getState() == state)
                .count();
    }

    /** 양쪽에 있는 단위를 견준다. 한쪽에만 있으면 그쪽 수량과 0 을 비교한 것이 된다. */
    private List<ReconcileDiff> compareUnits(ReconcileDefinition definition, ReconcileRun run,
                                             Map<UUID, BigDecimal> left,
                                             Map<UUID, BigDecimal> right) {
        Set<UUID> allUnits = new LinkedHashSet<>();
        allUnits.addAll(left.keySet());
        allUnits.addAll(right.keySet());

        log.debug("단위 비교 시작 — 실행={} 비교대상={}단위(좌={} 우={}) 허용오차={}",
                run.getId(), allUnits.size(), left.size(), right.size(),
                definition.getTolerance());

        List<ReconcileDiff> found = new ArrayList<>();
        int withinTolerance = 0;
        for (UUID unitId : allUnits) {
            BigDecimal leftQty = left.getOrDefault(unitId, BigDecimal.ZERO);
            BigDecimal rightQty = right.getOrDefault(unitId, BigDecimal.ZERO);
            BigDecimal delta = leftQty.subtract(rightQty);

            // 허용 오차 이내는 차이로 보지 않는다. 잡음이 쌓이면 진짜 문제가 묻힌다.
            if (delta.abs().compareTo(definition.getTolerance()) <= 0) {
                withinTolerance++;
                log.debug("허용 오차 이내라 기록하지 않는다 — 실행={} 단위={} 좌={} 우={} 델타={}",
                        run.getId(), unitId, leftQty, rightQty, delta);
                continue;
            }

            DiffType type = delta.signum() > 0 ? DiffType.LEFT_MORE : DiffType.RIGHT_MORE;
            var promotion = promoter.decide(definition.getId(), run.getId(), unitId, type);

            log.debug("차이 기록 — 실행={} 단위={} 좌={} 우={} 델타={} 유형={} 상태={} 최초관찰실행={}",
                    run.getId(), unitId, leftQty, rightQty, delta, type,
                    promotion.state(), promotion.firstSeenRunId());

            found.add(ReconcileDiff.of(run.getTenantId(), run.getId(), unitId,
                    unitCodeOf(unitId), leftQty, rightQty, delta, type,
                    promotion.state(), promotion.firstSeenRunId()));
        }

        log.debug("단위 비교 완료 — 실행={} 비교대상={}단위 차이={}건 허용오차이내={}건",
                run.getId(), allUnits.size(), found.size(), withinTolerance);
        return found;
    }

    /**
     * 어느 단위에도 속하지 않은 품목을 남긴다.
     *
     * <p><b>미매칭은 실패가 아니라 결과의 한 유형이다.</b> 이것 때문에 대조 전체를 중단하면
     * 나머지 결과도 못 보게 되고, 사람이 매칭을 끝낼 때까지 대조를 아예 쓸 수 없다.
     */
    private int recordUnmatched(ReconcileDefinition definition, ReconcileRun run,
                                BaseAtResolver.Aligned aligned, List<ReconcileDiff> found) {
        int count = 0;
        // 원천마다 자기가 실제로 가진 시각으로 훑는다. 칸 시작 시각으로 훑으면 그 시각에
        // 담긴 자료가 없어 «전부 미매칭» 이 된다.
        count += addUnmatched(run, definition, aligned.left(), definition.getLeftSource(),
                DiffType.UNMATCHED_LEFT, found);
        count += addUnmatched(run, definition, aligned.right(), definition.getRightSource(),
                DiffType.UNMATCHED_RIGHT, found);
        return count;
    }

    private int addUnmatched(ReconcileRun run, ReconcileDefinition definition, Instant baseAt,
                             String source, DiffType type, List<ReconcileDiff> found) {
        var items = aggregator.unmatched(run.getTenantId(), source, baseAt,
                definition.getCompareField());

        if (!items.isEmpty()) {
            // 실패는 아니지만 사람이 연결해 줘야 사라지는 잔여다. 쌓이면 대조 범위가 줄어든다.
            log.warn("미매칭 품목 {}건 — 실행={} 원천={} 유형={} 기준시각={}. 정합 단위 연결이 필요하다",
                    items.size(), run.getId(), source, type, baseAt);
        }

        for (var item : items) {
            boolean isLeft = type == DiffType.UNMATCHED_LEFT;
            log.debug("미매칭 기록 — 실행={} 원천={} 유형={} 품목={} 품명={} 수량={}",
                    run.getId(), source, type, item.itemRef(), item.rawName(), item.quantity());
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
        return units.findById(unitId).map(ReconcileUnit::getCode).orElseGet(() -> {
            log.warn("정합 단위를 찾지 못해 단위 코드를 비워 둔다 — 단위={}", unitId);
            return "";
        });
    }
}
