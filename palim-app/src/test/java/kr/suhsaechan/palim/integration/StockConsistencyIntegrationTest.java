package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.sku.Sku;
import kr.suhsaechan.palim.sku.SkuRepository;
import kr.suhsaechan.palim.sku.StockMovement;
import kr.suhsaechan.palim.sku.StockMovementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 스냅샷과 이력 누적합의 일치를 검증한다.
 *
 * <p>본 시스템은 스스로를 "재고의 유일한 기준"으로 정의하므로, 자신의 불일치를 감지하지
 * 못하면 틀어진 상태로 장기간 운영된다. 설계서 5.3의 대조 배치가 성립하는지가 여기서 결정된다.
 */
@Transactional
class StockConsistencyIntegrationTest extends IntegrationTest {

    @Autowired
    private SkuRepository skuRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Test
    @DisplayName("초기 재고 이력을 남기면 누적합이 현재 재고와 일치한다")
    void 초기_재고_이력이_있으면_누적합이_일치한다() {
        Sku sku = skuRepository.save(Sku.register("SKU-CONSISTENCY-1", "정합성 테스트 상품", 100, 10));
        stockMovementRepository.save(StockMovement.ofInitialStock(sku.getId(), 100));

        assertThat(stockMovementRepository.sumDeltaBySkuId(sku.getId()))
                .isEqualTo(sku.getQuantity());
    }

    @Test
    @DisplayName("판매·입고·폐기·실사가 섞여도 누적합이 현재 재고와 일치한다")
    void 여러_변동을_거쳐도_누적합이_일치한다() {
        Sku sku = skuRepository.save(Sku.register("SKU-CONSISTENCY-2", "정합성 테스트 상품", 100, 10));
        stockMovementRepository.save(StockMovement.ofInitialStock(sku.getId(), 100));

        // 판매 3개
        sku.decrease(3);
        stockMovementRepository.save(
                StockMovement.ofSale(sku.getId(), 3, sku.getQuantity(), UuidV7.generate()));

        // 입고 20개
        sku.increase(20);
        stockMovementRepository.save(
                StockMovement.ofRestock(sku.getId(), 20, sku.getQuantity(), "정기 입고"));

        // 폐기 2개
        sku.decrease(2);
        stockMovementRepository.save(
                StockMovement.ofDisposal(sku.getId(), 2, sku.getQuantity(), "파손"));

        // 실사 조정 — 절대값으로 덮어쓴다
        int before = sku.getQuantity();
        sku.adjustTo(90);
        stockMovementRepository.save(
                StockMovement.ofAdjustment(sku.getId(), before, sku.getQuantity(), "월말 실사"));

        skuRepository.saveAndFlush(sku);

        assertThat(sku.getQuantity()).isEqualTo(90);
        assertThat(stockMovementRepository.sumDeltaBySkuId(sku.getId()))
                .as("이력 누적합이 현재 재고와 일치해야 한다")
                .isEqualTo(sku.getQuantity());
    }

    @Test
    @DisplayName("취소 복원 후에도 누적합이 현재 재고와 일치한다")
    void 취소_복원_후에도_누적합이_일치한다() {
        Sku sku = skuRepository.save(Sku.register("SKU-CONSISTENCY-3", "정합성 테스트 상품", 50, 5));
        stockMovementRepository.save(StockMovement.ofInitialStock(sku.getId(), 50));

        UUID orderLineId = UuidV7.generate();

        sku.decrease(4);
        stockMovementRepository.save(StockMovement.ofSale(sku.getId(), 4, sku.getQuantity(), orderLineId));

        sku.increase(4);
        stockMovementRepository.save(
                StockMovement.ofCancelRestore(sku.getId(), 4, sku.getQuantity(), orderLineId));

        skuRepository.saveAndFlush(sku);

        assertThat(sku.getQuantity()).isEqualTo(50);
        assertThat(stockMovementRepository.sumDeltaBySkuId(sku.getId())).isEqualTo(50);
    }

    @Test
    @DisplayName("비관적 락 조회가 동작한다")
    void 비관적_락으로_조회할_수_있다() {
        Sku saved = skuRepository.saveAndFlush(Sku.register("SKU-LOCK-1", "락 테스트 상품", 10, 5));

        assertThat(skuRepository.findForUpdateById(saved.getId()))
                .isPresent()
                .get()
                .extracting(Sku::getCode)
                .isEqualTo("SKU-LOCK-1");
    }
}
