package kr.suhsaechan.palim.sku;

import lombok.Getter;

/**
 * 재고보다 많은 수량을 차감하려 할 때 발생한다.
 *
 * <p>초과판매(오버셀링)가 실제로 일어났다는 신호이기도 하다. 채널 재고 동기화(F-08)가
 * 지연되는 동안 실재고를 넘는 주문이 접수되면 이 예외를 만나게 된다.
 */
@Getter
public class InsufficientStockException extends RuntimeException {

    private final String skuCode;
    private final int currentQuantity;
    private final int requestedQuantity;

    public InsufficientStockException(String skuCode, int currentQuantity, int requestedQuantity) {
        super("재고가 부족합니다. SKU=%s, 현재=%d, 요청=%d"
                .formatted(skuCode, currentQuantity, requestedQuantity));
        this.skuCode = skuCode;
        this.currentQuantity = currentQuantity;
        this.requestedQuantity = requestedQuantity;
    }
}
