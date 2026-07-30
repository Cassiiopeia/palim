package kr.suhsaechan.palim.collector;

import java.util.List;
import kr.suhsaechan.palim.order.OrderLine;
import kr.suhsaechan.palim.order.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 미매핑 주문의 재고 소급 반영 (F-04).
 *
 * <p>매핑되지 않은 상품의 주문은 저장되지만 재고에 반영되지 않는다. 발주자가 매핑을 등록하면
 * 그때 소급 반영해야 하는데, 등록 시점에 즉시 처리하는 경로와 주기적으로 훑는 경로를 모두 둔다.
 * 즉시 처리가 실패하더라도 주기 실행이 결국 정리하기 때문이다.
 *
 * <p><b>트랜잭션을 열지 않는다.</b> 항목별 트랜잭션은
 * {@link OrderIngestionService#applyStockRetroactively} 가 열며, 그래서 항목 하나가 실패해도
 * 나머지는 처리된다. 여러 항목을 한 트랜잭션에 묶으면 하나 때문에 전부 롤백된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnmappedOrderReconciler {

    private final OrderService orderService;
    private final OrderIngestionService orderIngestionService;

    /**
     * 미매핑으로 남은 주문 항목 전체를 훑어 매핑이 생긴 것을 반영한다.
     *
     * @return 소급 반영한 항목 수
     */
    @Scheduled(fixedDelayString = "${palim.collect.reconcile-delay:300000}")
    public int reconcileAll() {
        List<OrderLine> unmappedLines = orderService.findUnmappedLines();
        List<OrderLine> awaitingLines = orderService.findLinesAwaitingStock();

        int reconciled = 0;
        reconciled += apply(unmappedLines);
        reconciled += apply(awaitingLines);

        if (reconciled > 0) {
            log.info("미매핑 주문 소급 반영 완료 — {}건", reconciled);
        }
        return reconciled;
    }

    private int apply(List<OrderLine> lines) {
        int reconciled = 0;
        for (OrderLine line : lines) {
            try {
                if (orderIngestionService.applyStockRetroactively(line.getId())) {
                    reconciled++;
                }
            } catch (RuntimeException exception) {
                // 항목 하나의 실패가 나머지를 막지 않는다. 다음 주기에 재시도된다.
                log.error("소급 반영 실패 — 주문항목 {}", line.getId(), exception);
            }
        }
        return reconciled;
    }
}
