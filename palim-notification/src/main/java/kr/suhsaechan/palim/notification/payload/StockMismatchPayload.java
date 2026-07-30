package kr.suhsaechan.palim.notification.payload;

/**
 * 재고 정합성 불일치 알림 내용 (설계서 5.3).
 *
 * <p>재고 스냅샷과 이력 누적합이 어긋났다는 뜻이다. 본 시스템은 스스로를 "재고의 유일한
 * 기준"으로 정의하므로, 이 알림은 <b>기준 자체가 틀어졌다</b>는 신고다. 방치하면 발주 판단이
 * 잘못된 수치를 근거로 이뤄진다.
 *
 * @param skuCode          자사 SKU 코드
 * @param productName      상품명
 * @param snapshotQuantity {@code sku.quantity} 스냅샷 값
 * @param historySum       이력 누적합
 */
public record StockMismatchPayload(
        String skuCode,
        String productName,
        int snapshotQuantity,
        int historySum
) {

    /** 어긋난 크기. 부호는 스냅샷이 이력보다 큰지 작은지를 나타낸다. */
    public int difference() {
        return snapshotQuantity - historySum;
    }
}
