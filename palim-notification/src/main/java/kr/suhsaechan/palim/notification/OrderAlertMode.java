package kr.suhsaechan.palim.notification;

/**
 * 주문 알림 발송 방식 (F-02).
 *
 * <p>발주자는 초기에 "알림이 많아도 무방"하다고 판단했으나, 주문량에 따라 알림 과다로 알림을
 * 아예 확인하지 않게 되는 문제가 생길 수 있다. 그래서 설정으로 조절 가능하게 했다.
 */
public enum OrderAlertMode {

    /** 건별 즉시 발송. 기본값. */
    IMMEDIATE,

    /** N분 단위 묶음 발송. */
    BATCHED
}
