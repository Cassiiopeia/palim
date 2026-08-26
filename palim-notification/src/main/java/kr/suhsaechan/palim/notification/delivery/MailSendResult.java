package kr.suhsaechan.palim.notification.delivery;

/**
 * 메일 발송 결과.
 *
 * <p>발송 실패는 예외가 아니라 <b>정상 흐름의 일부</b>다. 더 중요한 것은 {@code retryable} 을
 * 부르는 쪽이 반드시 확인하게 만드는 것이다 — 예외로 표현하면 이 구분이 예외 타입이나 메시지
 * 문자열에 묻힌다(메신저 발송이 이미 같은 계약을 쓴다).
 *
 * @param success      보냈는가
 * @param retryable    다시 해 볼 여지가 있는가
 * @param errorMessage 실패 사유. <b>비밀번호를 담지 않는다</b>
 */
public record MailSendResult(boolean success, boolean retryable, String errorMessage) {

    private static final MailSendResult SENT = new MailSendResult(true, false, null);

    public static MailSendResult sent() {
        return SENT;
    }

    /** 다시 해도 안 되는 실패 — 인증 거부, 주소 오류. 계속 시도하면 계정이 잠긴다. */
    public static MailSendResult permanentFailure(String errorMessage) {
        return new MailSendResult(false, false, errorMessage);
    }

    /** 잠깐의 실패 — 연결 끊김, 시간 초과. */
    public static MailSendResult transientFailure(String errorMessage) {
        return new MailSendResult(false, true, errorMessage);
    }
}
