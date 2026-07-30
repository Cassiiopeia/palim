package kr.suhsaechan.palim.web.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.CommonErrorCode;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 전역 예외 처리.
 *
 * <p>이 핸들러는 예외 종류마다 분기하지 않는다. {@link BusinessException} 하나를 받아
 * {@link ErrorCode} 가 정한 HTTP 상태와 로그 레벨을 따를 뿐이다. <b>새로운 실패 유형이
 * 추가되어도 이 클래스는 고치지 않는다.</b>
 *
 * <p>로그 레벨을 ErrorCode 가 정하는 이유가 여기 있다. 중복 수집({@code ORDER_LINE_DUPLICATE})은
 * 정상 흐름 제어이므로 경고로 남으면 안 되는데, 핸들러에서 if 분기로 예외를 골라내면 그런
 * 특수 사례마다 이 파일을 수정하게 된다.
 *
 * <p>REST 응답 전용이다. Thymeleaf 화면의 오류 페이지는 별도로 다룬다.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ErrorMessageResolver errorMessageResolver;

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException exception, HttpServletRequest request) {

        ErrorCode errorCode = exception.getErrorCode();
        writeLog(errorCode, request, exception);

        String message = errorMessageResolver.resolve(errorCode, exception.messageArgs());
        return ResponseEntity.status(errorCode.httpStatus())
                .body(ErrorResponse.of(errorCode, message, request.getRequestURI(),
                        exception.getDetails()));
    }

    /**
     * Bean Validation 실패.
     *
     * <p>필드별 오류를 {@code details} 에 담는다. 클라이언트가 어느 입력을 고쳐야 하는지
     * 프로그램적으로 판단할 수 있어야 하기 때문이다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException exception, HttpServletRequest request) {

        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }

        ErrorCode errorCode = CommonErrorCode.VALIDATION_FAILED;
        log.debug("입력 검증 실패 — {} {} 필드 {}", request.getMethod(), request.getRequestURI(), fieldErrors);

        return ResponseEntity.status(errorCode.httpStatus())
                .body(ErrorResponse.of(errorCode, errorMessageResolver.resolve(errorCode),
                        request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException exception, HttpServletRequest request) {

        ErrorCode errorCode = CommonErrorCode.ACCESS_DENIED;
        log.warn("접근 거부 — {} {}", request.getMethod(), request.getRequestURI());

        return ResponseEntity.status(errorCode.httpStatus())
                .body(ErrorResponse.of(errorCode, errorMessageResolver.resolve(errorCode),
                        request.getRequestURI()));
    }

    /**
     * 트랜잭션 없이 변경 서비스를 호출한 경우.
     *
     * <p>도메인 서비스의 변경 메서드는 {@code Propagation.MANDATORY} 다(설계서 3.4). 이 예외가
     * 운영에서 발생하면 <b>설계 위반이며 반드시 코드를 고쳐야 한다</b> — 조용히 넘기면 재고 변경과
     * 이력 기록이 각각 커밋되어 정합성이 깨진다.
     */
    @ExceptionHandler(IllegalTransactionStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalTransactionState(
            IllegalTransactionStateException exception, HttpServletRequest request) {

        ErrorCode errorCode = CommonErrorCode.TRANSACTION_REQUIRED;
        log.error("트랜잭션 경계 위반 — {} {}. 호출 경로에 트랜잭션이 없습니다.",
                request.getMethod(), request.getRequestURI(), exception);

        return ResponseEntity.status(errorCode.httpStatus())
                .body(ErrorResponse.of(errorCode, errorMessageResolver.resolve(errorCode),
                        request.getRequestURI()));
    }

    /**
     * 예상하지 못한 실패.
     *
     * <p><b>내부 메시지를 응답에 담지 않는다.</b> 스택 트레이스나 SQL 문구가 노출되면 공격
     * 표면이 된다. 원인은 로그에만 남긴다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception exception, HttpServletRequest request) {

        ErrorCode errorCode = CommonErrorCode.INTERNAL_ERROR;
        log.error("처리하지 못한 오류 — {} {}", request.getMethod(), request.getRequestURI(), exception);

        return ResponseEntity.status(errorCode.httpStatus())
                .body(ErrorResponse.of(errorCode, errorMessageResolver.resolve(errorCode),
                        request.getRequestURI()));
    }

    /**
     * ErrorCode 가 정한 수준으로 로깅한다.
     *
     * <p>스택 트레이스는 ERROR 이상에서만 남긴다. 예상된 실패(404, 409)에 스택을 남기면 로그가
     * 무의미해진다.
     */
    private void writeLog(ErrorCode errorCode, HttpServletRequest request, BusinessException exception) {
        String location = "%s %s".formatted(request.getMethod(), request.getRequestURI());

        switch (errorCode.logLevel()) {
            case ERROR, FATAL -> log.error("{} — {} {}", errorCode.name(), location,
                    exception.getMessage(), exception);
            case WARN -> log.warn("{} — {} {}", errorCode.name(), location, exception.getMessage());
            case INFO -> log.info("{} — {} {}", errorCode.name(), location, exception.getMessage());
            case DEBUG, TRACE -> log.debug("{} — {} {}", errorCode.name(), location, exception.getMessage());
            case OFF -> {
                // 의도적으로 로깅하지 않는다
            }
        }
    }
}
