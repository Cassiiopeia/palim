package kr.suhsaechan.palim.sku;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import org.junit.jupiter.api.Test;

class StockMovementTest {

    private final UUID skuId = UuidV7.generate();
    private final UUID orderLineId = UuidV7.generate();

    @Test
    void 판매는_음수_delta로_기록된다() {
        StockMovement movement = StockMovement.ofSale(skuId, 3, 7, orderLineId);

        assertThat(movement.getDelta()).isEqualTo(-3);
        assertThat(movement.getReason()).isEqualTo(StockChangeReason.SALE);
        assertThat(movement.getQuantityAfter()).isEqualTo(7);
        assertThat(movement.getReferenceType()).isEqualTo(StockReferenceType.ORDER_LINE);
        assertThat(movement.getReferenceId()).isEqualTo(orderLineId);
    }

    @Test
    void 취소_복원은_양수_delta로_기록된다() {
        StockMovement movement = StockMovement.ofCancelRestore(skuId, 3, 10, orderLineId);

        assertThat(movement.getDelta()).isEqualTo(3);
        assertThat(movement.getReason()).isEqualTo(StockChangeReason.CANCEL_RESTORE);
    }

    @Test
    void 폐기는_음수_delta로_기록된다() {
        StockMovement movement = StockMovement.ofDisposal(skuId, 2, 8, "파손");

        assertThat(movement.getDelta()).isEqualTo(-2);
        assertThat(movement.getReason()).isEqualTo(StockChangeReason.DISPOSAL);
        assertThat(movement.getReferenceType()).isEqualTo(StockReferenceType.MANUAL);
    }

    /**
     * 실사 조정은 절대값으로 덮어쓰는 변경이지만, delta 에는 <b>변경 전후의 차이</b>가 들어가야 한다.
     * 그래야 이력 누적합과 현재 재고의 대조가 성립한다(설계서 5.3).
     */
    @Test
    void 실사_조정의_delta는_변경_전후_차이다() {
        StockMovement increased = StockMovement.ofAdjustment(skuId, 10, 50, "실사");
        StockMovement decreased = StockMovement.ofAdjustment(skuId, 10, 4, "실사");

        assertThat(increased.getDelta()).isEqualTo(40);
        assertThat(increased.getQuantityAfter()).isEqualTo(50);
        assertThat(decreased.getDelta()).isEqualTo(-6);
        assertThat(decreased.getQuantityAfter()).isEqualTo(4);
    }
}
