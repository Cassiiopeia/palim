package kr.suhsaechan.palim.sku;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 재고 변동 이력. append-only 다 (F-03).
 *
 * <p>모든 변동은 사유·수량·시각과 함께 기록되어 추적 가능하다. 이력은 감사 기록이므로
 * 수정·삭제하지 않는다.
 *
 * <p>{@code quantityAfter}를 함께 남기는 이유는, 이력만 보고도 당시 재고를 알 수 있어야
 * 사후 추적이 가능하기 때문이다. 이 값과 {@code delta} 누적합이 어긋나면 정합성이 깨진
 * 시점을 특정할 수 있다.
 */
@Getter
@Entity
@Table(name = "stock_movement")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockMovement extends BaseTimeEntity {

    @Id
    private UUID id;

    /** {@code palim-sku}의 Sku 식별자. 같은 모듈이지만 값 참조로 일관되게 다룬다. */
    @Column(nullable = false)
    private UUID skuId;

    /** 음수는 차감, 양수는 증가. */
    @Column(nullable = false)
    private int delta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StockChangeReason reason;

    /** 변동 직후 재고. 이력만으로 당시 상태를 추적할 수 있게 한다. */
    @Column(nullable = false)
    private int quantityAfter;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private StockReferenceType referenceType;

    /** 판매면 주문 항목 식별자. 도메인 모듈을 의존하지 않으므로 값으로만 갖는다. */
    private UUID referenceId;

    @Column(length = 500)
    private String memo;

    @Version
    private Long version;

    @Builder(access = AccessLevel.PRIVATE)
    private StockMovement(UUID skuId, int delta, StockChangeReason reason, int quantityAfter,
                          StockReferenceType referenceType, UUID referenceId, String memo) {
        this.id = UuidV7.generate();
        this.skuId = skuId;
        this.delta = delta;
        this.reason = reason;
        this.quantityAfter = quantityAfter;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.memo = memo;
    }

    /**
     * SKU 등록 시의 초기 재고.
     *
     * <p><b>이 이력을 남기지 않으면 설계서 5.3의 대조 배치가 성립하지 않는다.</b>
     * {@code Sku.register}가 초기 수량을 받는데 대응하는 이력이 없으면
     * {@code SUM(delta)}가 {@code quantity}보다 항상 초기 수량만큼 적게 나와,
     * 정상 상태를 불일치로 오판하게 된다.
     *
     * <p>도입 시점에 발주자가 실물 재고를 실사해 입력하는 1회성 작업이므로 조정으로 기록한다(F-03).
     */
    public static StockMovement ofInitialStock(UUID skuId, int quantity) {
        return manual(skuId, quantity, StockChangeReason.ADJUSTMENT, quantity, "초기 재고 실사");
    }

    /** 주문 수집에 따른 자동 차감. */
    public static StockMovement ofSale(UUID skuId, int quantity, int quantityAfter, UUID orderLineId) {
        return StockMovement.builder()
                .skuId(skuId)
                .delta(-quantity)
                .reason(StockChangeReason.SALE)
                .quantityAfter(quantityAfter)
                .referenceType(StockReferenceType.ORDER_LINE)
                .referenceId(orderLineId)
                .build();
    }

    /** 취소·반품에 따른 자동 복원. */
    public static StockMovement ofCancelRestore(UUID skuId, int quantity, int quantityAfter, UUID orderLineId) {
        return StockMovement.builder()
                .skuId(skuId)
                .delta(quantity)
                .reason(StockChangeReason.CANCEL_RESTORE)
                .quantityAfter(quantityAfter)
                .referenceType(StockReferenceType.ORDER_LINE)
                .referenceId(orderLineId)
                .build();
    }

    /** 발주자 입력에 따른 입고. */
    public static StockMovement ofRestock(UUID skuId, int quantity, int quantityAfter, String memo) {
        return manual(skuId, quantity, StockChangeReason.RESTOCK, quantityAfter, memo);
    }

    /** 발주자 입력에 따른 폐기·분실. */
    public static StockMovement ofDisposal(UUID skuId, int quantity, int quantityAfter, String memo) {
        return manual(skuId, -quantity, StockChangeReason.DISPOSAL, quantityAfter, memo);
    }

    /**
     * 실사 조정.
     *
     * <p>절대값으로 덮어쓰는 변경이므로 {@code delta}는 변경 전후의 차이로 계산해 넣는다.
     * 그래야 이력 누적합과 현재 재고의 대조가 성립한다(설계서 5.3).
     */
    public static StockMovement ofAdjustment(UUID skuId, int quantityBefore, int quantityAfter, String memo) {
        return manual(skuId, quantityAfter - quantityBefore, StockChangeReason.ADJUSTMENT, quantityAfter, memo);
    }

    private static StockMovement manual(UUID skuId, int delta, StockChangeReason reason,
                                        int quantityAfter, String memo) {
        return StockMovement.builder()
                .skuId(skuId)
                .delta(delta)
                .reason(reason)
                .quantityAfter(quantityAfter)
                .referenceType(StockReferenceType.MANUAL)
                .memo(memo)
                .build();
    }
}
