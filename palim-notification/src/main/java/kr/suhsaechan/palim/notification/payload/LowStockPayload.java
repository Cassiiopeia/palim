package kr.suhsaechan.palim.notification.payload;

/**
 * 안전재고 미달 알림 내용 (F-05).
 *
 * @param skuCode           자사 SKU 코드
 * @param productName       상품명
 * @param currentStock      현재 재고
 * @param safetyThreshold   설정된 임계치
 * @param averageDailySales 최근 7일 평균 판매량
 */
public record LowStockPayload(
        String skuCode,
        String productName,
        int currentStock,
        int safetyThreshold,
        double averageDailySales
) {

    /**
     * 예상 소진일수.
     *
     * <p>판매가 없으면 계산할 수 없으므로 빈 값이다. 0 을 반환하면 "오늘 소진"으로 오해된다.
     */
    public Double expectedDaysLeft() {
        if (averageDailySales <= 0) {
            return null;
        }
        return currentStock / averageDailySales;
    }
}
