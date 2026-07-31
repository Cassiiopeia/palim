package kr.suhsaechan.palim.notification;

/**
 * 알림 종류.
 *
 * <p>앞의 네 개는 기능 명세서가 정의한 사용자 대상 알림이고, 뒤의 네 개는 시스템 자기 감시
 * 알림이다. 별도 관측 스택(Prometheus 등)을 두지 않는 이유는 이미 운영자에게 도달하는 경로가
 * 있기 때문이다 — 시스템 장애도 같은 경로로 보낸다(설계서 9.3).
 */
public enum NotificationType {

    /** 신규 주문 (F-02). */
    NEW_ORDER("신규 주문"),

    /** 안전재고 미달 (F-05). */
    LOW_STOCK("재고 부족"),

    /** 재고 0 도달 — 긴급 (F-05). */
    OUT_OF_STOCK("품절"),

    /**
     * 오버셀링 — 실재고를 초과해 판매되어 재고가 음수가 되었다.
     *
     * <p>채널 재고 동기화 지연 중에 발생한다. 출고 불가 상태이므로 발주자가 즉시 조치해야 한다.
     */
    OVERSELL("초과판매"),

    /** 미매핑 상품 주문 수집 (F-04). 방치하면 재고가 조용히 틀어진다. */
    UNMAPPED_PRODUCT("미매핑 상품"),

    /** 일일 요약 리포트 (F-06). */
    DAILY_REPORT("일일 리포트"),

    /** 채널 수집 연속 실패 (A-10). */
    COLLECT_FAILURE("수집 실패"),

    /** 채널 재고 전송 실패·차단 (F-08). */
    STOCK_PUSH_FAILURE("재고 전송 실패"),

    /** 재고 스냅샷과 이력 누적합 불일치 (설계서 5.3). */
    STOCK_MISMATCH("재고 불일치");

    private final String displayName;

    NotificationType(String displayName) {
        this.displayName = displayName;
    }

    /** 화면·메시지 표시 이름. */
    public String displayName() {
        return displayName;
    }

    /**
     * 긴급 알림 여부.
     *
     * <p>긴급 알림은 <b>야간 보류와 묶음 발송에서 제외</b>된다. 재고가 음수가 되었거나 수집이
     * 멈춘 상황은 아침까지 기다릴 수 없다. 발주자가 알림 과다를 우려해 야간 보류를 켰더라도,
     * 매출 손실로 직결되는 사안은 즉시 알려야 한다.
     */
    public boolean isUrgent() {
        return this == OVERSELL
                || this == OUT_OF_STOCK
                || this == COLLECT_FAILURE
                || this == STOCK_PUSH_FAILURE
                || this == STOCK_MISMATCH;
    }

    /**
     * 묶음 발송 대상 여부.
     *
     * <p>주문 알림만 묶는다. 주문량이 많을 때 알림 과다로 아예 확인하지 않게 되는 문제를
     * 막기 위한 기능이므로(F-02), 빈도가 낮은 다른 알림은 묶을 이유가 없다.
     */
    public boolean isBatchable() {
        return this == NEW_ORDER;
    }
}
