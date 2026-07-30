package kr.suhsaechan.palim.order;

import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.logging.LogLevel;
import org.springframework.http.HttpStatus;

/**
 * 주문 도메인 실패 (접두사 {@code O}).
 */
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {

    ORDER_NOT_FOUND("O001", HttpStatus.NOT_FOUND, LogLevel.WARN),

    ORDER_LINE_NOT_FOUND("O002", HttpStatus.NOT_FOUND, LogLevel.WARN),

    /**
     * 이미 수집된 주문 항목이다.
     *
     * <p><b>오류가 아니라 정상 흐름 제어다.</b> 수집 커서는 구간을 겹쳐 조회하므로(설계서 5.4)
     * 같은 주문이 반복 수집되는 것이 정상이며, 이 코드는 "이미 처리했으니 재고를 차감하지 말라"는
     * 신호다. 그래서 로그 레벨이 {@link LogLevel#DEBUG} 다 — WARN 으로 두면 정상 동작이
     * 경고 로그를 가득 채운다.
     *
     * <p>이 예외가 발생하면 데이터베이스 트랜잭션은 rollback-only 가 된다. 따라서 수집 조율은
     * 주문 1건 단위로 트랜잭션을 열어야 한다.
     */
    ORDER_LINE_DUPLICATE("O003", HttpStatus.CONFLICT, LogLevel.DEBUG),

    INVALID_ORDER_QUANTITY("O004", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    SKU_ID_REQUIRED("O005", HttpStatus.BAD_REQUEST, LogLevel.DEBUG);

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
