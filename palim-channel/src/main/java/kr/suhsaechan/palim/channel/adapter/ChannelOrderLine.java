package kr.suhsaechan.palim.channel.adapter;

/**
 * 채널 어댑터가 반환하는 주문 항목 공통 형식.
 *
 * <p>채널별 응답 구조 차이를 어댑터가 흡수해 이 형식으로 맞춘다. 재고 차감의 단위이기도 하다.
 *
 * @param channelLineNo      채널이 부여한 주문 항목 식별자. 중복 판정의 일부다
 * @param channelProductNo   채널 상품코드
 * @param channelOptionNo    옵션 단위 식별자(쿠팡 vendorItemId 등). 옵션이 없으면 null
 * @param channelProductName 수집 시점의 채널 상품명
 * @param quantity           주문 수량
 * @param unitPrice          단가(원)
 * @param amount             금액(원)
 */
public record ChannelOrderLine(
        String channelLineNo,
        String channelProductNo,
        String channelOptionNo,
        String channelProductName,
        int quantity,
        long unitPrice,
        long amount
) {

    public ChannelOrderLine {
        if (channelLineNo == null || channelLineNo.isBlank()) {
            throw new IllegalArgumentException("채널 주문 항목 식별자가 없습니다");
        }
        if (channelProductNo == null || channelProductNo.isBlank()) {
            throw new IllegalArgumentException("채널 상품코드가 없습니다");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("주문 수량은 1 이상이어야 합니다: " + quantity);
        }
    }
}
