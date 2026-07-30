package kr.suhsaechan.palim.auth;

import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.logging.LogLevel;
import org.springframework.http.HttpStatus;

/**
 * 인증 도메인 실패 (접두사 {@code A}).
 */
@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    ADMIN_ACCOUNT_NOT_FOUND("A001", HttpStatus.NOT_FOUND, LogLevel.WARN),

    INVALID_USERNAME("A002", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    INVALID_PASSWORD("A003", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    /** 계정도 없고 초기 비밀번호도 지정되지 않아 기동할 수 없다. */
    ADMIN_PASSWORD_REQUIRED("A004", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR);

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
