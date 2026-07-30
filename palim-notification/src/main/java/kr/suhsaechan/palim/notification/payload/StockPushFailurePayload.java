package kr.suhsaechan.palim.notification.payload;

/**
 * 채널 재고 전송 실패·차단 알림 내용 (F-08).
 *
 * <p>재고 전송은 채널에 데이터를 기록하는 유일한 경로이므로 실패가 실제 판매에 영향을 준다.
 * 특히 변동량 상한 초과로 차단된 경우는 <b>재고 계산 오류의 신호</b>일 수 있어 즉시 확인해야 한다.
 *
 * @param channelName     채널 표시명
 * @param skuCode         자사 SKU 코드
 * @param productName     상품명
 * @param beforeQuantity  전송 전 채널 재고. 조회하지 못했으면 null
 * @param afterQuantity   전송하려 한 수량
 * @param blocked         변동량 상한 초과로 차단된 경우 true
 * @param errorMessage    실패 사유
 */
public record StockPushFailurePayload(
        String channelName,
        String skuCode,
        String productName,
        Integer beforeQuantity,
        int afterQuantity,
        boolean blocked,
        String errorMessage
) {
}
