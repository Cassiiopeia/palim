package kr.suhsaechan.palim.notification.payload;

/**
 * 품절 알림 내용 (F-05).
 *
 * <p>재고 0 도달은 안전재고 경고와 별개의 긴급 알림이다. 판매가 계속되면 곧 오버셀링으로
 * 넘어가므로 즉시 인지해야 한다.
 *
 * @param skuCode     자사 SKU 코드
 * @param productName 상품명
 */
public record OutOfStockPayload(
        String skuCode,
        String productName
) {
}
