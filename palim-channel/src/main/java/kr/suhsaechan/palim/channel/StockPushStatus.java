package kr.suhsaechan.palim.channel;

/**
 * 채널 재고 전송 결과 (F-08).
 */
public enum StockPushStatus {

    SUCCESS,

    FAILED,

    /**
     * 변동량 상한을 초과해 전송이 차단된 경우.
     *
     * <p>재고 계산 오류로 0을 전송하면 해당 상품이 전 채널에서 품절 처리되어 매출 손실이
     * 발생한다. 상한 초과는 사고 신호이므로 전송을 막고 경고를 보낸다.
     */
    BLOCKED
}
