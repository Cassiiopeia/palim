package kr.suhsaechan.palim.monitor;

import java.util.List;
import kr.suhsaechan.palim.incident.IncidentService;
import kr.suhsaechan.palim.incident.IncidentType;
import kr.suhsaechan.palim.notification.NotificationType;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.notification.payload.StockMismatchPayload;
import kr.suhsaechan.palim.sku.Sku;
import kr.suhsaechan.palim.sku.SkuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 스냅샷과 이력 누적합을 대조한다.
 *
 * <h2>이 배치가 없으면</h2>
 *
 * <p>본 시스템은 스스로를 <b>"재고의 유일한 기준"</b>으로 정의한다(F-03). 원본을 자처하는
 * 시스템이 자신의 불일치를 감지하지 못하면 <b>틀어진 상태로 장기간 운영된다.</b> 발주자는
 * 실물 재고가 안 맞는 것을 발견한 시점에야 인지하고, 그때는 원인 추적이 불가능하다.
 *
 * <p>하루 한 번 자기 검산하는 비용은 SKU 당 쿼리 하나다.
 *
 * <h2>검산식</h2>
 *
 * <pre>{@code SUM(stock_movement.delta) == sku.quantity}</pre>
 *
 * <p>이 식이 성립하려면 두 조건이 필요하다(03-DOMAIN 참조).
 *
 * <ul>
 *   <li>SKU 등록 시 초기 재고를 이력으로 남긴다 — 없으면 누적합이 항상 부족해 <b>정상 상태를
 *       매번 불일치로 오판한다</b>
 *   <li>실사 조정의 {@code delta} 는 변경 전후의 차이다 — 절대값을 넣으면 누적합이 어긋난다
 * </ul>
 *
 * <p>오버셀링으로 음수가 된 재고도 정상 대조 대상이다. 이력과 스냅샷이 함께 음수로 내려가므로
 * 식이 그대로 성립한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockConsistencyChecker {

    private final SkuService skuService;
    private final OutboxService outboxService;
    private final IncidentService incidentService;
    private final MonitorProperties monitorProperties;

    /**
     * 전 SKU 를 대조한다.
     *
     * <p>트랜잭션을 하나로 두는 이유는 조회만 하기 때문이다. 알림 등록도 같은 트랜잭션에
     * 참여하는데, 이는 문제가 되지 않는다 — 대조 도중 실패하면 알림도 함께 롤백되어 다음
     * 주기에 다시 검사하는 것이 맞다.
     *
     * @return 불일치가 발견된 SKU 수
     */
    @Transactional
    @Scheduled(fixedDelayString = "${palim.monitor.stock-consistency-delay:PT24H}")
    public int check() {
        List<Sku> targets = skuService.findAllActive();
        int mismatched = 0;

        for (Sku sku : targets) {
            if (skuService.isConsistent(sku.getId())) {
                continue;
            }
            mismatched++;
            reportMismatch(sku);
        }

        if (mismatched > 0) {
            log.error("재고 정합성 불일치 {}건 발견 — SKU {}개 중", mismatched, targets.size());
        } else {
            log.info("재고 정합성 대조 완료 — SKU {}개 전부 일치", targets.size());
        }
        return mismatched;
    }

    private void reportMismatch(Sku sku) {
        int historySum = skuService.sumMovementDelta(sku.getId());

        log.error("재고 불일치 — SKU {} 스냅샷 {} vs 이력 누적합 {}",
                sku.getCode(), sku.getQuantity(), historySum);

        // 불일치가 지속되면 매 주기 알림이 나가므로 억제한다.
        outboxService.enqueueIfNotRecent(
                NotificationType.STOCK_MISMATCH,
                "STOCK_MISMATCH:" + sku.getCode(),
                monitorProperties.mismatchAlertInterval(),
                new StockMismatchPayload(sku.getCode(), sku.getName(), sku.getQuantity(), historySum));

        // 인시던트는 억제와 무관하게 누적한다 (#35) — 알림은 스팸 방지, 인시던트는 기록이 목적이다.
        // 해결 전까지 매 주기 발생 횟수가 쌓여 "며칠째 지속 중인지"가 화면에 남는다.
        incidentService.report(
                IncidentType.STOCK_MISMATCH,
                "STOCK_MISMATCH:" + sku.getCode(),
                "SKU %s %s 재고 불일치".formatted(sku.getCode(), sku.getName()),
                "스냅샷 %d vs 이력 누적합 %d — 원인 확인 후 실사 조정으로 맞춰야 한다"
                        .formatted(sku.getQuantity(), historySum));
    }
}
