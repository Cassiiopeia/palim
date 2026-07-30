package kr.suhsaechan.palim.sku;

import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.logging.LogLevel;
import org.springframework.http.HttpStatus;

/**
 * SKU · 재고 도메인 실패 (접두사 {@code S}).
 */
@Getter
@RequiredArgsConstructor
public enum SkuErrorCode implements ErrorCode {

    SKU_NOT_FOUND("S001", HttpStatus.NOT_FOUND, LogLevel.WARN),

    SKU_CODE_DUPLICATE("S002", HttpStatus.CONFLICT, LogLevel.DEBUG),

    /**
     * 수동 차감에서 실재고를 초과했다.
     *
     * <p>판매 차감은 오버셀링을 허용하므로 이 코드를 쓰지 않는다. 폐기·분실처럼 사람이
     * 입력하는 경로에서만 발생하며, 입력 실수일 가능성이 높다.
     */
    INSUFFICIENT_STOCK("S003", HttpStatus.CONFLICT, LogLevel.WARN),

    INVALID_STOCK_AMOUNT("S004", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    INVALID_SAFETY_THRESHOLD("S005", HttpStatus.BAD_REQUEST, LogLevel.DEBUG);

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
