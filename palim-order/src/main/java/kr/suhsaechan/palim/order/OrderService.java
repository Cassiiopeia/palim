package kr.suhsaechan.palim.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 도메인 서비스.
 *
 * <h2>중복 수집 판정</h2>
 *
 * <p>수집 커서는 구간을 겹쳐서 조회하므로(설계서 5.4) 같은 주문이 반복 수집되는 것이 정상이다.
 * 중복 판정의 최종 근거는 <b>데이터베이스 유니크 제약</b>이며, 조회는 1차 필터로만 쓴다.
 * "조회했더니 없어서 삽입한다"만으로는 수집이 중첩되는 순간 뚫린다(설계서 5.1).
 *
 * <p>제약 위반은 {@link ErrorCode#ORDER_LINE_DUPLICATE} 로 변환해 전파한다. 이때 트랜잭션은
 * rollback-only 가 되므로 <b>수집 조율은 주문 1건 단위로 트랜잭션을 열어야 한다.</b>
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderLineRepository orderLineRepository;

    // ------------------------------------------------------------------
    // 수집 저장
    // ------------------------------------------------------------------

    /**
     * 주문을 저장하거나, 이미 있으면 기존 것을 반환한다.
     *
     * <p>주문 헤더는 여러 항목이 공유하므로 중복을 예외로 다루지 않는다. 실제 중복 방어는
     * 항목 단위에서 이뤄진다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Order saveOrderIfAbsent(ChannelCode channelCode, String channelOrderNo, Instant orderedAt,
                                   Instant collectedAt, String buyerName, long totalAmount) {
        return orderRepository.findByChannelCodeAndChannelOrderNo(channelCode, channelOrderNo)
                .orElseGet(() -> orderRepository.save(
                        Order.collect(channelCode, channelOrderNo, orderedAt, collectedAt,
                                buyerName, totalAmount)));
    }

    /**
     * 주문 항목을 저장한다.
     *
     * <p>{@code saveAndFlush} 를 쓰는 이유는 유니크 제약 위반을 이 지점에서 드러내기 위함이다.
     * flush 하지 않으면 커밋 시점에 터져 어느 항목이 중복인지 알 수 없다.
     *
     * @throws BusinessException 이미 수집된 항목인 경우 ORDER_LINE_DUPLICATE
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderLine saveOrderLine(UUID orderId, ChannelCode channelCode, String channelOrderNo,
                                   String channelLineNo, String channelProductNo, String channelOptionNo,
                                   String channelProductName, UUID skuId,
                                   int quantity, long unitPrice, long amount) {
        OrderLine line = OrderLine.collect(orderId, channelCode, channelOrderNo, channelLineNo,
                channelProductNo, channelOptionNo, channelProductName, skuId,
                quantity, unitPrice, amount);
        try {
            return orderLineRepository.saveAndFlush(line);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.ORDER_LINE_DUPLICATE, e,
                    channelCode, channelOrderNo, channelLineNo);
        }
    }

    /**
     * 이미 수집된 항목인지 확인한다.
     *
     * <p>1차 필터 전용이다. 이 결과만으로 중복을 판정해서는 안 된다 — 조회와 삽입 사이에
     * 다른 수집이 끼어들 수 있다. 최종 방어는 {@link #saveOrderLine} 의 제약 위반 처리다.
     */
    @Transactional(readOnly = true)
    public boolean existsOrderLine(ChannelCode channelCode, String channelOrderNo, String channelLineNo) {
        return orderLineRepository.existsByChannelCodeAndChannelOrderNoAndChannelLineNo(
                channelCode, channelOrderNo, channelLineNo);
    }

    // ------------------------------------------------------------------
    // 상태 · 재고 반영 표시
    // ------------------------------------------------------------------

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(UUID orderId) {
        getOrder(orderId).cancel();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markReturned(UUID orderId) {
        getOrder(orderId).markReturned();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markStockApplied(UUID orderLineId) {
        getOrderLine(orderLineId).markStockApplied();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markStockRestored(UUID orderLineId) {
        getOrderLine(orderLineId).markStockRestored();
    }

    /** 매핑 완료 후 SKU 를 연결한다 (F-04 소급 반영). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assignSku(UUID orderLineId, UUID skuId) {
        getOrderLine(orderLineId).assignSku(skuId);
    }

    // ------------------------------------------------------------------
    // 조회
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Order getOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, orderId));
    }

    @Transactional(readOnly = true)
    public OrderLine getOrderLine(UUID orderLineId) {
        return orderLineRepository.findById(orderLineId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_LINE_NOT_FOUND, orderLineId));
    }

    @Transactional(readOnly = true)
    public Optional<Order> findOrder(ChannelCode channelCode, String channelOrderNo) {
        return orderRepository.findByChannelCodeAndChannelOrderNo(channelCode, channelOrderNo);
    }

    @Transactional(readOnly = true)
    public List<OrderLine> findLinesOf(UUID orderId) {
        return orderLineRepository.findByOrderId(orderId);
    }

    /** 미매핑 주문 항목 — 매핑 필요 알림 대상 (F-04). */
    @Transactional(readOnly = true)
    public List<OrderLine> findUnmappedLines() {
        return orderLineRepository.findBySkuIdIsNullOrderByCreatedAtDesc();
    }

    /** 특정 채널 상품에 대한 미매핑 항목 — 매핑 등록 직후 소급 대상을 찾는다. */
    @Transactional(readOnly = true)
    public List<OrderLine> findUnmappedLinesFor(ChannelCode channelCode, String channelProductNo,
                                                String channelOptionNo) {
        return orderLineRepository.findUnmappedFor(channelCode, channelProductNo, channelOptionNo);
    }

    /** 매핑은 됐으나 재고가 반영되지 않은 항목 — 소급 반영 실행 대상. */
    @Transactional(readOnly = true)
    public List<OrderLine> findLinesAwaitingStock() {
        return orderLineRepository.findBySkuIdIsNotNullAndStockAppliedFalseOrderByCreatedAtAsc();
    }

    /** 일일 리포트·엑셀 내보내기용 기간 조회 (F-06, F-07). */
    @Transactional(readOnly = true)
    public List<Order> findOrdersBetween(Instant from, Instant to) {
        return orderRepository.findByOrderedAtBetweenOrderByOrderedAtDesc(from, to);
    }
}
