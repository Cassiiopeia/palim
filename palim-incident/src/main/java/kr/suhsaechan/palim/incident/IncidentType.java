package kr.suhsaechan.palim.incident;

/**
 * 인시던트 유형 (#34).
 *
 * <p>전부 발주자 조치가 필요한 사건이다. 알림({@code NotificationType})과 1:1 이 아니다 —
 * 알림은 "알리는 것"이고 인시던트는 "처리를 추적하는 것"이라, 신규 주문·일일 리포트처럼
 * 조치가 필요 없는 알림은 인시던트가 되지 않는다.
 */
public enum IncidentType {

    /** 실재고를 초과해 판매됐다. 출고 불가 상태 — 재고 확보나 주문 취소가 필요하다. */
    OVERSELL("초과판매"),

    /** 재고 수량과 변동 이력 누적합이 어긋났다. 원인을 찾기 전까지 재고를 믿을 수 없다. */
    STOCK_MISMATCH("재고 불일치"),

    /** 매핑 없는 상품의 주문이 수집됐다. 매핑 전까지 재고에 반영되지 않는다. */
    UNMAPPED_PRODUCT("미매핑 상품"),

    /** 연속 실패로 채널 수집이 자동 중단됐다. 인증정보·IP 확인 후 재활성화가 필요하다. */
    COLLECT_STOPPED("수집 중단");

    private final String displayName;

    IncidentType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
