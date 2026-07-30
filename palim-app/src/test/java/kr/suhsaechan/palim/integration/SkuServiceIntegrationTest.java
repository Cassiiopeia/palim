package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.sku.Sku;
import kr.suhsaechan.palim.sku.SkuService;
import kr.suhsaechan.palim.sku.SkuErrorCode;
import kr.suhsaechan.palim.sku.StockChangeReason;
import kr.suhsaechan.palim.sku.StockMovement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 변경이 이력 기록과 항상 짝으로 일어나는지 검증한다.
 *
 * <p>둘이 분리되면 대조 배치(설계서 5.3)가 즉시 불일치를 보고한다. 서비스가 두 작업을 함께
 * 수행하므로 호출자는 {@code StockMovement} 를 직접 만들지 않는다.
 */
@Transactional
class SkuServiceIntegrationTest extends IntegrationTest {

    @Autowired
    private SkuService skuService;

    private Sku 등록(String code, int quantity, int threshold) {
        return skuService.register(code, "테스트 상품", quantity, threshold);
    }

    @Test
    @DisplayName("등록하면 초기 재고 이력이 함께 남아 대조가 일치한다")
    void 등록_시_초기_이력이_남는다() {
        Sku sku = 등록("SVC-SKU-1", 100, 10);

        assertThat(skuService.findMovements(sku.getId()))
                .singleElement()
                .satisfies(movement -> {
                    assertThat(movement.getReason()).isEqualTo(StockChangeReason.ADJUSTMENT);
                    assertThat(movement.getDelta()).isEqualTo(100);
                    assertThat(movement.getQuantityAfter()).isEqualTo(100);
                });
        assertThat(skuService.isConsistent(sku.getId())).isTrue();
    }

    @Test
    @DisplayName("판매 차감 시 이력이 함께 기록되고 대조가 일치한다")
    void 판매_차감_시_이력이_기록된다() {
        Sku sku = 등록("SVC-SKU-2", 50, 5);
        UUID orderLineId = UuidV7.generate();

        skuService.decreaseForSale(sku.getId(), 3, orderLineId);

        assertThat(skuService.getById(sku.getId()).getQuantity()).isEqualTo(47);
        assertThat(skuService.findMovements(sku.getId()))
                .hasSize(2)
                .anySatisfy(movement -> {
                    assertThat(movement.getReason()).isEqualTo(StockChangeReason.SALE);
                    assertThat(movement.getDelta()).isEqualTo(-3);
                    assertThat(movement.getReferenceId()).isEqualTo(orderLineId);
                });
        assertThat(skuService.isConsistent(sku.getId())).isTrue();
    }

    @Test
    @DisplayName("입고·폐기·실사·취소복원을 거쳐도 대조가 일치한다")
    void 여러_변동을_거쳐도_대조가_일치한다() {
        Sku sku = 등록("SVC-SKU-3", 100, 10);
        UUID orderLineId = UuidV7.generate();

        skuService.decreaseForSale(sku.getId(), 5, orderLineId);
        skuService.increaseForCancel(sku.getId(), 5, orderLineId);
        skuService.restock(sku.getId(), 20, "정기 입고");
        skuService.dispose(sku.getId(), 3, "파손");
        skuService.adjust(sku.getId(), 90, "월말 실사");

        assertThat(skuService.getById(sku.getId()).getQuantity()).isEqualTo(90);
        assertThat(skuService.isConsistent(sku.getId()))
                .as("모든 변경이 이력과 짝을 이뤄야 한다")
                .isTrue();
        assertThat(skuService.findMovements(sku.getId())).hasSize(6);
    }

    /**
     * 판매 차감은 오버셀링을 허용한다. 음수 재고 상태에서도 대조가 성립해야 한다 —
     * 성립하지 않으면 오버셀링이 발생할 때마다 대조 배치가 거짓 경고를 낸다.
     */
    @Test
    @DisplayName("오버셀링이 발생해도 대조가 일치한다")
    void 오버셀링_후에도_대조가_일치한다() {
        Sku sku = 등록("SVC-SKU-4", 2, 5);

        boolean oversold = skuService.decreaseForSale(sku.getId(), 3, UuidV7.generate());

        assertThat(oversold).isTrue();
        assertThat(skuService.getById(sku.getId()).getQuantity()).isEqualTo(-1);
        assertThat(skuService.isConsistent(sku.getId())).isTrue();
    }

    @Test
    @DisplayName("중복 SKU 코드는 거부된다")
    void 중복_코드는_거부된다() {
        등록("SVC-SKU-5", 10, 5);

        assertThatThrownBy(() -> 등록("SVC-SKU-5", 20, 5))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SkuErrorCode.SKU_CODE_DUPLICATE);
    }

    @Test
    @DisplayName("안전재고 미달 목록에 포함된다")
    void 안전재고_미달을_조회한다() {
        Sku sku = 등록("SVC-SKU-6", 10, 5);
        skuService.decreaseForSale(sku.getId(), 6, UuidV7.generate());

        assertThat(skuService.findBelowThreshold())
                .extracting(Sku::getCode)
                .contains("SVC-SKU-6");
    }

    @Test
    @DisplayName("실사 조정 이력의 delta 는 변경 전후 차이다")
    void 실사_조정_delta는_차이다() {
        Sku sku = 등록("SVC-SKU-7", 10, 5);

        skuService.adjust(sku.getId(), 50, "실사");

        assertThat(skuService.findMovements(sku.getId()))
                .filteredOn(movement -> "실사".equals(movement.getMemo()))
                .singleElement()
                .extracting(StockMovement::getDelta)
                .isEqualTo(40);
    }
}
