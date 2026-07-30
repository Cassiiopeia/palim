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
    NEW_ORDER,

    /** 안전재고 미달 (F-05). */
    LOW_STOCK,

    /** 재고 0 도달 — 긴급 (F-05). */
    OUT_OF_STOCK,

    /**
     * 오버셀링 — 실재고를 초과해 판매되어 재고가 음수가 되었다.
     *
     * <p>채널 재고 동기화 지연 중에 발생한다. 출고 불가 상태이므로 발주자가 즉시 조치해야 한다.
     */
    OVERSELL,

    /** 미매핑 상품 주문 수집 (F-04). 방치하면 재고가 조용히 틀어진다. */
    UNMAPPED_PRODUCT,

    /** 일일 요약 리포트 (F-06). */
    DAILY_REPORT,

    /** 채널 수집 연속 실패 (A-10). */
    COLLECT_FAILURE,

    /** 채널 재고 전송 실패·차단 (F-08). */
    STOCK_PUSH_FAILURE,

    /** 재고 스냅샷과 이력 누적합 불일치 (설계서 5.3). */
    STOCK_MISMATCH
}
