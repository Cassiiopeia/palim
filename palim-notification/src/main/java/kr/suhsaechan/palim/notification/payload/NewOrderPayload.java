package kr.suhsaechan.palim.notification.payload;

import java.time.Instant;

/**
 * 신규 주문 알림 내용 (F-02).
 *
 * <p>SKU 코드·상품명·현재 재고를 문자열과 숫자로 받는다. 알림 도메인은 {@code palim-sku} 를
 * 의존하지 않으므로 엔티티를 받을 수 없고, 받아서도 안 된다 — 알림은 <b>발송 시점의 값</b>을
 * 그대로 보여줘야 하며 나중에 조회하면 이미 변한 값이 나간다.
 *
 * @param channelName  채널 표시명
 * @param channelOrderNo 채널 주문번호
 * @param skuCode      자사 SKU 코드
 * @param productName  상품명
 * @param quantity     주문 수량
 * @param amount       금액(원)
 * @param orderedAt    주문 시각
 * @param currentStock 차감 후 재고
 */
public record NewOrderPayload(
        String channelName,
        String channelOrderNo,
        String skuCode,
        String productName,
        int quantity,
        long amount,
        Instant orderedAt,
        int currentStock
) {
}
