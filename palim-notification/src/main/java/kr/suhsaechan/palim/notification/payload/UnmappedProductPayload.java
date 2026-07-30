package kr.suhsaechan.palim.notification.payload;

/**
 * 미매핑 상품 주문 알림 내용 (F-04).
 *
 * <p>매핑되지 않은 상품의 주문은 재고에 반영되지 않는다. 이를 방치하면 재고가 조용히 틀어지고,
 * 발주자는 실물과 안 맞는 것을 나중에 발견한다. 그래서 즉시 알린다.
 *
 * @param channelName        채널 표시명
 * @param channelOrderNo     채널 주문번호
 * @param channelProductNo   채널 상품코드 — 발주자가 이 값으로 매핑을 등록한다
 * @param channelOptionNo    옵션 식별자. 없으면 null
 * @param channelProductName 채널 상품명
 * @param quantity           주문 수량
 */
public record UnmappedProductPayload(
        String channelName,
        String channelOrderNo,
        String channelProductNo,
        String channelOptionNo,
        String channelProductName,
        int quantity
) {
}
