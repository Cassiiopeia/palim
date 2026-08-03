package kr.suhsaechan.palim.collector;

import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.channel.adapter.ChannelOrder;
import kr.suhsaechan.palim.channel.adapter.ChannelOrderLine;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.incident.IncidentService;
import kr.suhsaechan.palim.incident.IncidentType;
import kr.suhsaechan.palim.mapping.ProductMappingService;
import kr.suhsaechan.palim.notification.NotificationType;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.notification.payload.NewOrderPayload;
import kr.suhsaechan.palim.notification.payload.OverSellPayload;
import kr.suhsaechan.palim.notification.payload.UnmappedProductPayload;
import kr.suhsaechan.palim.order.Order;
import kr.suhsaechan.palim.order.OrderLine;
import kr.suhsaechan.palim.order.OrderService;
import kr.suhsaechan.palim.sku.Sku;
import kr.suhsaechan.palim.sku.SkuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 수집 조율.
 *
 * <p>도메인 4개를 관통하는 유일한 지점이다 — 매핑 조회 → 주문 저장 → 재고 차감 → Outbox 등록.
 * 도메인 모듈은 서로를 모르므로 이 조합은 여기서만 가능하다.
 *
 * <h2>주문 1건 = 트랜잭션 1개</h2>
 *
 * <p>{@link Propagation#REQUIRES_NEW} 다. {@code saveOrderLine} 이 유니크 제약 위반을 만나면
 * 트랜잭션이 rollback-only 가 되므로, 여러 주문을 한 트랜잭션에서 처리하면 <b>중복 하나 때문에
 * 정상 주문까지 롤백된다.</b>
 *
 * <p>호출자({@link ChannelCollectRunner})는 이 서비스와 <b>다른 빈</b>이어야 한다. 같은 클래스
 * 내 호출은 프록시를 타지 않아 전파 설정이 무효가 된다.
 *
 * <h2>원자성</h2>
 *
 * <p>주문 저장·재고 차감·Outbox 등록이 한 트랜잭션이다. 중간에 실패하면 셋 다 롤백되므로
 * "재고는 빠졌는데 주문이 없다" 또는 "주문은 있는데 알림이 안 갔다" 상태가 생기지 않는다(A-14).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderIngestionService {

    private final OrderService orderService;
    private final ProductMappingService productMappingService;
    private final SkuService skuService;
    private final OutboxService outboxService;
    private final IncidentService incidentService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IngestResult ingest(ChannelOrder channelOrder, Instant collectedAt) {
        ChannelCode channelCode = channelOrder.channelCode();
        String channelOrderNo = channelOrder.channelOrderNo();

        Order order = orderService.saveOrderIfAbsent(
                channelCode, channelOrderNo, channelOrder.orderedAt(), collectedAt,
                channelOrder.buyerName(), channelOrder.totalAmount());

        int newCount = 0;
        int duplicateCount = 0;
        int unmappedCount = 0;
        int oversoldCount = 0;

        for (ChannelOrderLine line : channelOrder.lines()) {
            // 1차 필터. 겹침 수집 구간에서 대부분의 중복이 여기서 걸러진다.
            // 이것만으로 판정하지 않는다 — 최종 방어는 saveOrderLine 의 유니크 제약이다.
            if (orderService.existsOrderLine(channelCode, channelOrderNo, line.channelLineNo())) {
                duplicateCount++;
                continue;
            }

            UUID skuId = productMappingService
                    .resolveSkuId(channelCode, line.channelProductNo(), line.channelOptionNo())
                    .orElse(null);

            OrderLine savedLine = orderService.saveOrderLine(
                    order.getId(), channelCode, channelOrderNo, line.channelLineNo(),
                    line.channelProductNo(), line.channelOptionNo(), line.channelProductName(),
                    skuId, line.quantity(), line.unitPrice(), line.amount());

            if (skuId == null) {
                unmappedCount++;
                enqueueUnmapped(channelOrder, line);
                reportUnmappedIncident(channelCode, line);
                continue;
            }

            boolean oversold = skuService.decreaseForSale(skuId, line.quantity(), savedLine.getId());
            orderService.markStockApplied(savedLine.getId());

            Sku sku = skuService.getById(skuId);
            enqueueNewOrder(channelOrder, line, sku);

            if (oversold) {
                oversoldCount++;
                enqueueOversell(channelOrder, line, sku);
                reportOversellIncident(sku, line.quantity(),
                        "채널 %s 주문 %s".formatted(channelCode.displayName(), channelOrderNo));
            }
            newCount++;
        }

        IngestResult result = IngestResult.of(channelCode, channelOrderNo,
                newCount, duplicateCount, unmappedCount, oversoldCount);

        if (newCount > 0 || unmappedCount > 0) {
            log.info("주문 수집 — {} {} (신규 {}, 중복 {}, 미매핑 {}, 오버셀 {})",
                    channelCode, channelOrderNo, newCount, duplicateCount, unmappedCount, oversoldCount);
        }
        return result;
    }

    /**
     * 매핑 완료 후 재고를 소급 반영한다 (F-04).
     *
     * <p>미매핑으로 저장된 주문 항목에 매핑이 생겼는지 확인하고, 있으면 SKU 를 연결하고 재고를
     * 차감한다. <b>항목 1건 단위 트랜잭션</b>이므로 하나가 실패해도 나머지는 처리된다.
     *
     * @return 소급 반영했으면 true, 아직 매핑이 없으면 false
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean applyStockRetroactively(UUID orderLineId) {
        OrderLine line = orderService.getOrderLine(orderLineId);
        if (line.isStockApplied()) {
            return false;
        }

        UUID skuId = line.getSkuId();
        if (skuId == null) {
            skuId = productMappingService
                    .resolveSkuId(line.getChannelCode(), line.getChannelProductNo(),
                            line.getChannelOptionNo())
                    .orElse(null);
            if (skuId == null) {
                return false;
            }
            orderService.assignSku(orderLineId, skuId);
        }

        boolean oversold = skuService.decreaseForSale(skuId, line.getQuantity(), orderLineId);
        orderService.markStockApplied(orderLineId);

        Sku sku = skuService.getById(skuId);
        log.info("미매핑 주문 소급 반영 — {} {} 항목 {} → SKU {} 재고 {}",
                line.getChannelCode(), line.getChannelOrderNo(), line.getChannelLineNo(),
                sku.getCode(), sku.getQuantity());

        if (oversold) {
            outboxService.enqueue(NotificationType.OVERSELL, new OverSellPayload(
                    line.getChannelCode().displayName(),
                    line.getChannelOrderNo(),
                    sku.getCode(),
                    sku.getName(),
                    line.getQuantity(),
                    sku.getQuantity()));
            reportOversellIncident(sku, line.getQuantity(),
                    "미매핑 소급 반영 — 채널 %s 주문 %s".formatted(
                            line.getChannelCode().displayName(), line.getChannelOrderNo()));
        }
        return true;
    }

    private void enqueueNewOrder(ChannelOrder channelOrder, ChannelOrderLine line, Sku sku) {
        outboxService.enqueue(NotificationType.NEW_ORDER, new NewOrderPayload(
                channelOrder.channelCode().displayName(),
                channelOrder.channelOrderNo(),
                sku.getCode(),
                sku.getName(),
                line.quantity(),
                line.amount(),
                channelOrder.orderedAt(),
                sku.getQuantity()));
    }

    /**
     * 미매핑 알림.
     *
     * <p>매핑되지 않은 상품의 주문은 재고에 반영되지 않는다. 방치하면 재고가 조용히 틀어지고
     * 발주자는 실물과 안 맞는 것을 한참 뒤에 발견한다(F-04).
     */
    private void enqueueUnmapped(ChannelOrder channelOrder, ChannelOrderLine line) {
        outboxService.enqueue(NotificationType.UNMAPPED_PRODUCT, new UnmappedProductPayload(
                channelOrder.channelCode().displayName(),
                channelOrder.channelOrderNo(),
                line.channelProductNo(),
                line.channelOptionNo(),
                line.channelProductName(),
                line.quantity()));
    }

    /**
     * 오버셀 인시던트 (#35). 알림과 별개로 해결 전까지 남는 기록이다.
     *
     * <p>수집 트랜잭션에 참여하므로 수집이 롤백되면 인시던트도 남지 않는다.
     */
    private void reportOversellIncident(Sku sku, int quantity, String context) {
        incidentService.report(
                IncidentType.OVERSELL,
                "OVERSELL:" + sku.getCode(),
                "SKU %s %s 초과판매".formatted(sku.getCode(), sku.getName()),
                "%s 수량 %d 처리 후 재고 %d — 출고 가능 여부를 확인하고 실재고를 맞춰야 한다"
                        .formatted(context, quantity, sku.getQuantity()));
    }

    /** 미매핑 인시던트 (#35). 매핑 등록 후 해결 처리는 사람이 한다 — 소급 반영 확인까지가 조치다. */
    private void reportUnmappedIncident(ChannelCode channelCode, ChannelOrderLine line) {
        String optionPart = line.channelOptionNo() == null ? "-" : line.channelOptionNo();
        incidentService.report(
                IncidentType.UNMAPPED_PRODUCT,
                "UNMAPPED_PRODUCT:%s:%s:%s".formatted(
                        channelCode, line.channelProductNo(), optionPart),
                "%s 상품 %s 미매핑".formatted(channelCode.displayName(), line.channelProductNo()),
                "상품명 %s (옵션 %s) — 매핑을 등록하면 재고가 소급 반영된다"
                        .formatted(line.channelProductName(), optionPart));
    }

    /** 오버셀링 알림. 출고 불가 상태이므로 발주자가 즉시 조치해야 한다. */
    private void enqueueOversell(ChannelOrder channelOrder, ChannelOrderLine line, Sku sku) {
        log.warn("오버셀링 발생 — SKU {} 재고 {} (채널 {} 주문 {})",
                sku.getCode(), sku.getQuantity(),
                channelOrder.channelCode(), channelOrder.channelOrderNo());

        outboxService.enqueue(NotificationType.OVERSELL, new OverSellPayload(
                channelOrder.channelCode().displayName(),
                channelOrder.channelOrderNo(),
                sku.getCode(),
                sku.getName(),
                line.quantity(),
                sku.getQuantity()));
    }
}
