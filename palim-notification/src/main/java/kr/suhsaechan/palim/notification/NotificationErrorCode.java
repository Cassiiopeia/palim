package kr.suhsaechan.palim.notification;

import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.logging.LogLevel;
import org.springframework.http.HttpStatus;

/**
 * 알림 도메인 실패 (접두사 {@code N}).
 */
@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {

    NOTIFICATION_NOT_FOUND("N001", HttpStatus.NOT_FOUND, LogLevel.WARN),

    /** 부트스트랩이 수행되지 않았다. 알림이 발송되지 않는 상태이므로 즉시 조치해야 한다. */
    NOTIFICATION_SETTING_NOT_INITIALIZED("N002", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    PAYLOAD_SERIALIZE_FAILED("N003", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    PAYLOAD_DESERIALIZE_FAILED("N004", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    INVALID_BATCH_INTERVAL("N005", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    INVALID_QUIET_HOURS("N006", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    INVALID_REPEAT_HOURS("N007", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    /** 텔레그램 발송 실패. Outbox 에 남아 재시도된다. */
    TELEGRAM_SEND_FAILED("N008", HttpStatus.BAD_GATEWAY, LogLevel.ERROR);

    private final String code;
    private final HttpStatus httpStatus;
    private final LogLevel logLevel;

    @Override
    public String code() {
        return code;
    }

    @Override
    public HttpStatus httpStatus() {
        return httpStatus;
    }

    @Override
    public LogLevel logLevel() {
        return logLevel;
    }
}
