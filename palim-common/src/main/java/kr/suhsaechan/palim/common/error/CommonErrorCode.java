package kr.suhsaechan.palim.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.logging.LogLevel;
import org.springframework.http.HttpStatus;

/**
 * 도메인에 속하지 않는 공통 실패.
 *
 * <p>특정 도메인의 실패는 해당 모듈의 ErrorCode 에 정의한다. 여기에는 어느 도메인에도
 * 속하지 않는 것만 둔다.
 */
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    /** 예상하지 못한 실패. 응답에 내부 메시지를 노출하지 않는다. */
    INTERNAL_ERROR("C001", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    /** 입력값이 규칙에 맞지 않는다. */
    INVALID_INPUT("C002", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    /** Bean Validation 실패. 필드별 오류는 details 에 담긴다. */
    VALIDATION_FAILED("C003", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    /** 인증이 필요하다. */
    UNAUTHORIZED("C004", HttpStatus.UNAUTHORIZED, LogLevel.DEBUG),

    /** 권한이 없다. */
    ACCESS_DENIED("C005", HttpStatus.FORBIDDEN, LogLevel.WARN),

    /** 트랜잭션 없이 변경 서비스를 호출했다. 설계 위반이므로 반드시 고쳐야 한다. */
    TRANSACTION_REQUIRED("C006", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR);

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
