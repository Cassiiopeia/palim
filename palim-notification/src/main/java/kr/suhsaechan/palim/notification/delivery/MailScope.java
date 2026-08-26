package kr.suhsaechan.palim.notification.delivery;

import kr.suhsaechan.palim.notification.NotificationType;

/**
 * 메일로 <b>무엇까지</b> 받을 것인가.
 *
 * <p>전부 받으면 메일함이 잡음이 되고, 잡음이 되는 순간 그 알림은 없는 것과 같아진다. 반대로
 * 요약만 받으면 급한 일이 하루 늦게 도착한다. 어느 쪽이 맞는지는 쓰는 사람이 정한다.
 */
public enum MailScope {

    /** 하루 한 통 요약만. 급한 것은 메신저로 받는다는 전제다. */
    DIGEST_ONLY("요약만"),

    /** 요약 + 급한 것. 메신저를 안 보는 사람에게 맞다. */
    DIGEST_AND_URGENT("요약과 급한 것"),

    /** 전부. 기록을 메일함에 남기고 싶을 때. */
    ALL("전부");

    private final String displayName;

    MailScope(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 이 종류를 메일로 보내는가. */
    public boolean includes(NotificationType type) {
        return switch (this) {
            case ALL -> true;
            case DIGEST_AND_URGENT -> type == NotificationType.RECONCILE_DIGEST || type.isUrgent();
            case DIGEST_ONLY -> type == NotificationType.RECONCILE_DIGEST;
        };
    }
}
