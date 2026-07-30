package kr.suhsaechan.palim.notification;

/**
 * Outbox 발송 상태.
 *
 * <p>{@code PENDING} 으로 기록된 뒤 큐를 거쳐 발송되면 {@code SENT} 가 된다. 큐가 유실되거나
 * 애플리케이션이 재기동되어도 {@code PENDING} 행이 남아 있어 이어서 발송된다(A-14).
 */
public enum OutboxStatus {

    PENDING,

    SENT,

    /** 재시도 한도를 넘겨 포기한 상태. 사람이 확인해야 한다. */
    FAILED
}
