package kr.suhsaechan.palim.sku;

/**
 * 재고 변동 사유 (F-03).
 */
public enum StockChangeReason {

    /** 주문 수집 시 자동 차감. */
    SALE("판매"),

    /** 발주자 입력으로 증가. */
    RESTOCK("입고"),

    /** 채널에서 취소·반품 정보 수집 시 자동 복원. */
    CANCEL_RESTORE("취소·반품 복원"),

    /** 실사 조정. 절대값으로 덮어쓴다. */
    ADJUSTMENT("실사 조정"),

    /** 폐기·분실. */
    DISPOSAL("폐기·분실");

    private final String displayName;

    StockChangeReason(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
