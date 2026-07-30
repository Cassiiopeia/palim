package kr.suhsaechan.palim.web.sku;

import java.util.UUID;

/**
 * 재고 화면 표시용.
 *
 * @param oversold   <b>음수 재고.</b> 출고해야 할 수량이 실재고를 초과한 상태다
 * @param consistent 스냅샷과 이력 누적합이 일치하는지. 불일치는 기준값이 틀어졌다는 뜻이다
 */
public record SkuView(
        UUID id,
        String code,
        String name,
        int quantity,
        int safetyThreshold,
        boolean belowThreshold,
        boolean outOfStock,
        boolean oversold,
        double averageDailySales,
        boolean consistent
) {

    /**
     * 예상 소진일수.
     *
     * <p>판매가 없으면 계산할 수 없다. 0 을 반환하면 "오늘 소진"으로 오해된다.
     */
    public Double expectedDaysLeft() {
        if (averageDailySales <= 0 || quantity <= 0) {
            return null;
        }
        return quantity / averageDailySales;
    }

    /**
     * 화면 강조 등급.
     *
     * <p>음수 재고가 가장 시급하다 — 이미 판매됐는데 출고할 물건이 없는 상태다.
     */
    public String severity() {
        if (oversold) {
            return "oversold";
        }
        if (outOfStock) {
            return "out";
        }
        if (belowThreshold) {
            return "low";
        }
        return "normal";
    }
}
