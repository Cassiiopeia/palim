package kr.suhsaechan.palim.order;

/**
 * 주문 상태.
 *
 * <p>주문은 삭제하지 않는다. 취소·반품은 상태 전이로 처리하고 재고를 복원한다(F-03).
 * 반품·교환·취소의 <b>처리 기능</b>은 개발 범위에서 제외되지만, 알림과 재고 복원 대상에는
 * 포함되므로 상태는 관리한다.
 */
public enum OrderStatus {

    PLACED("주문"),
    CANCELLED("취소"),
    RETURNED("반품");

    private final String displayName;

    OrderStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
