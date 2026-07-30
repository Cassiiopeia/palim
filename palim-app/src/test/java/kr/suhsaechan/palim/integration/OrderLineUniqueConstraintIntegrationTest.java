package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.order.Order;
import kr.suhsaechan.palim.order.OrderLine;
import kr.suhsaechan.palim.order.OrderLineRepository;
import kr.suhsaechan.palim.order.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 중복 수집 차단을 검증한다 (A-02).
 *
 * <p>"조회 후 없으면 삽입"은 수집이 중첩되는 순간 뚫린다. 유니크 제약이 유일하게 믿을 수 있는
 * 방어선이므로, 제약이 실제로 존재하고 동작하는지 확인해야 한다(설계서 5.1).
 */
@Transactional
class OrderLineUniqueConstraintIntegrationTest extends IntegrationTest {

    private static final ChannelCode CHANNEL = ChannelCode.COUPANG;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderLineRepository orderLineRepository;

    private Order 주문을_저장한다(String channelOrderNo) {
        Instant now = Instant.now();
        return orderRepository.saveAndFlush(
                Order.collect(CHANNEL, channelOrderNo, now, now, "구매자", 39_800L));
    }

    private OrderLine 주문항목(Order order, String channelLineNo) {
        return OrderLine.collect(order.getId(), CHANNEL, order.getChannelOrderNo(), channelLineNo,
                "PRODUCT-1", null, "테스트 상품", null, 2, 19_900L, 39_800L);
    }

    @Test
    @DisplayName("동일 채널·주문번호·라인번호 재삽입은 제약 위반으로 차단된다")
    void 중복_주문항목_삽입은_차단된다() {
        Order order = 주문을_저장한다("ORDER-DUP-1");
        orderLineRepository.saveAndFlush(주문항목(order, "LINE-1"));

        assertThatThrownBy(() -> orderLineRepository.saveAndFlush(주문항목(order, "LINE-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("라인번호가 다르면 같은 주문에 여러 항목을 저장할 수 있다")
    void 라인번호가_다르면_저장된다() {
        Order order = 주문을_저장한다("ORDER-MULTI-1");

        orderLineRepository.saveAndFlush(주문항목(order, "LINE-1"));
        orderLineRepository.saveAndFlush(주문항목(order, "LINE-2"));

        assertThat(orderLineRepository.findByOrderId(order.getId())).hasSize(2);
    }

    @Test
    @DisplayName("동일 채널 주문번호 재삽입은 주문 단위에서도 차단된다")
    void 중복_주문_삽입은_차단된다() {
        주문을_저장한다("ORDER-DUP-2");

        assertThatThrownBy(() -> 주문을_저장한다("ORDER-DUP-2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("미매핑 주문 항목도 저장된다 — sku_id 는 nullable FK 다")
    void 미매핑_주문항목도_저장된다() {
        Order order = 주문을_저장한다("ORDER-UNMAPPED-1");

        OrderLine saved = orderLineRepository.saveAndFlush(주문항목(order, "LINE-1"));

        assertThat(saved.getSkuId()).isNull();
        assertThat(saved.isMapped()).isFalse();
        assertThat(saved.isStockApplied()).isFalse();
    }
}
