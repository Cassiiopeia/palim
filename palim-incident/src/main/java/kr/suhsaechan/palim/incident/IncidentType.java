package kr.suhsaechan.palim.incident;

/**
 * 인시던트 종류.
 *
 * <p>로드맵이 확정한 3종만 둔다. 수집 실패는 수집 모니터(#30)가 현재 상태를 보여주므로
 * 제외했다 — 이력 관리 필요가 확인되면 그때 추가한다.
 *
 * <p>{@code NotificationType} 과 별개 enum 인 이유는 모듈 격리다. 도메인 모듈은 서로를
 * 의존하지 않으므로 palim-notification 의 enum 을 가져다 쓸 수 없고, 애초에 대상도 다르다 —
 * 알림은 발송할 사건 전부, 인시던트는 사람이 마감해야 하는 문제만.
 */
public enum IncidentType {

    /** 실재고를 초과해 판매되어 재고가 음수가 되었다. 출고 불가 — 즉시 조치 대상. */
    OVERSELL("초과판매"),

    /** 재고 스냅샷과 이력 누적합 불일치. 방치하면 재고 기준 자체를 믿을 수 없게 된다. */
    STOCK_MISMATCH("재고 불일치"),

    /** 미매핑 상품 주문 수집. 방치하면 재고가 조용히 틀어진다. */
    UNMAPPED_PRODUCT("미매핑 상품");

    private final String displayName;

    IncidentType(String displayName) {
        this.displayName = displayName;
    }

    /** 화면 표시 이름. */
    public String displayName() {
        return displayName;
    }
}
