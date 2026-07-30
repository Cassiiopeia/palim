package kr.suhsaechan.palim.notification.telegram;

/**
 * 텔레그램 발송 결과.
 *
 * <p>발송 실패는 예외가 아니라 <b>정상 흐름의 일부</b>다. 네트워크는 끊기고 API 는 느려지며
 * 그때마다 예외를 던져 스택 트레이스를 남기는 것은 과하다. 더 중요한 것은
 * {@code retryable} 을 호출자가 반드시 확인하게 만드는 것이다 — 예외로 표현하면 이 구분이
 * 메시지 문자열이나 예외 타입에 묻힌다.
 *
 * @param success      발송 성공 여부
 * @param retryable    재시도로 성공할 여지가 있는지
 * @param errorMessage 실패 사유
 */
public record TelegramSendResult(
        boolean success,
        boolean retryable,
        String errorMessage
) {

    private static final TelegramSendResult SENT = new TelegramSendResult(true, false, null);

    /**
     * 발송 성공.
     *
     * <p>이름이 {@code success()} 가 아닌 이유는, record 컴포넌트 {@code success} 와 이름이
     * 겹치면 컴파일러가 accessor 로 취급해 반환 타입이 {@code boolean} 이어야 하기 때문이다.
     */
    public static TelegramSendResult sent() {
        return SENT;
    }

    /**
     * 재시도해도 성공하지 않는 실패.
     *
     * <p>잘못된 chat_id, 봇 차단, 토큰 무효 같은 경우다. 계속 시도하면 호출 제한만 소모한다.
     */
    public static TelegramSendResult permanentFailure(String errorMessage) {
        return new TelegramSendResult(false, false, errorMessage);
    }

    /** 일시적 실패 — 서버 오류, 타임아웃, 호출 제한. */
    public static TelegramSendResult transientFailure(String errorMessage) {
        return new TelegramSendResult(false, true, errorMessage);
    }
}
