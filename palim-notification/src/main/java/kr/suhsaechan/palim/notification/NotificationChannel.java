package kr.suhsaechan.palim.notification;

/**
 * 알림을 <b>어디로</b> 보내는가.
 *
 * <p>글자로 흘리지 않고 값으로 두는 이유 — 오타가 조용히 「보내지 않음」 이 된다. 값으로 두면
 * 중계의 갈래와 화면 표시가 컴파일 시점에 묶인다.
 */
public enum NotificationChannel {

    /** 메신저. 짧게, 즉시 본다. 제목이라는 자리가 없어 첫 줄이 그 노릇을 한다. */
    TELEGRAM("텔레그램"),

    /** 메일. 제목만 보고 열지 말지 정할 수 있다 — 하루 한 통 요약이 여기로 간다. */
    EMAIL("메일");

    private final String displayName;

    NotificationChannel(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
