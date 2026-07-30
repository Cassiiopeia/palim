package kr.suhsaechan.palim.mapping;

import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.logging.LogLevel;
import org.springframework.http.HttpStatus;

/**
 * 상품 매핑 도메인 실패 (접두사 {@code M}).
 */
@Getter
@RequiredArgsConstructor
public enum MappingErrorCode implements ErrorCode {

    PRODUCT_MAPPING_NOT_FOUND("M001", HttpStatus.NOT_FOUND, LogLevel.WARN),

    PRODUCT_MAPPING_DUPLICATE("M002", HttpStatus.CONFLICT, LogLevel.DEBUG),

    SKU_ID_REQUIRED("M003", HttpStatus.BAD_REQUEST, LogLevel.DEBUG);

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
