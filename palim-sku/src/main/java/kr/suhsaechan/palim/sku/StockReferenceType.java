package kr.suhsaechan.palim.sku;

/**
 * 재고 변동의 근거가 되는 대상 종류.
 *
 * <p>도메인 모듈끼리 의존하지 않으므로 실제 대상은 {@code UUID} 값으로만 참조한다.
 * 이 enum이 그 값을 어떤 테이블에서 찾아야 하는지 알려준다.
 */
public enum StockReferenceType {

    /** 주문 항목 — {@code palim-order}의 order_line */
    ORDER_LINE,

    /** 발주자가 화면에서 직접 입력 */
    MANUAL
}
