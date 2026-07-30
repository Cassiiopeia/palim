package kr.suhsaechan.palim.notification.payload;

/**
 * 오버셀링 알림 내용.
 *
 * <p>채널 재고 동기화(F-08)가 반영되기 전에 실재고를 초과하는 주문이 접수되면 재고가 음수가
 * 된다. 음수는 "출고해야 할 빚"을 뜻하며 발주자가 즉시 조치해야 하는 상태다.
 *
 * @param channelName    채널 표시명
 * @param channelOrderNo 채널 주문번호
 * @param skuCode        자사 SKU 코드
 * @param productName    상품명
 * @param quantity       이번 주문 수량
 * @param currentStock   차감 후 재고. <b>음수다</b>
 */
public record OverSellPayload(
        String channelName,
        String channelOrderNo,
        String skuCode,
        String productName,
        int quantity,
        int currentStock
) {

    /** 부족 수량 — 확보해야 할 물량이다. */
    public int shortageQuantity() {
        return currentStock < 0 ? -currentStock : 0;
    }
}
