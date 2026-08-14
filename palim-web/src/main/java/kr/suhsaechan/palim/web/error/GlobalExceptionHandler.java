package kr.suhsaechan.palim.web.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

        ErrorCode errorCode = ErrorCode.VALIDATION_FAILED;
        log.debug("입력 검증 실패 — {} {} 필드 {}", request.getMethod(), request.getRequestURI(), fieldErrors);

        return ResponseEntity.status(errorCode.httpStatus())
                .body(ErrorResponse.of(errorCode, errorMessageResolver.resolve(errorCode),
                        request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException exception, HttpServletRequest request) {

        ErrorCode errorCode = ErrorCode.ACCESS_DENIED;
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

        ErrorCode errorCode = ErrorCode.TRANSACTION_REQUIRED;
        log.error("트랜잭션 경계 위반 — {} {}. 호출 경로에 트랜잭션이 없습니다.",
                request.getMethod(), request.getRequestURI(), exception);

        return ResponseEntity.status(errorCode.httpStatus())
                .body(ErrorResponse.of(errorCode, errorMessageResolver.resolve(errorCode),
                        request.getRequestURI()));
    }

    /**
     * 없는 정적 파일 요청.
     *
     * <p>브라우저가 자동으로 요청하는 {@code /favicon.ico} 같은 것들이다. 이것을 "처리하지 못한
     * 오류"로 다루면 <b>페이지를 열 때마다 ERROR 로그가 쌓이고, 그 사이에 진짜 장애가 묻힌다.</b>
     * 404 로 조용히 돌려준다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException exception,
                                                          HttpServletRequest request) {
        log.debug("없는 정적 자원 — {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /**
     * 오래 열어 둔 연결이 시간을 다한 경우.
     *
     * <p>세션 감시(SSE)는 몇 분간 열어 두는 연결이고, 시간이 다하면 브라우저가 알아서 다시
     * 연결한다. <b>정상 동작이다.</b> 그런데 이것을 「처리하지 못한 오류」로 다루면 화면을
     * 열어 둔 사람 수만큼 10분마다 ERROR 가 쌓이고, <b>그 사이에 진짜 장애가 묻힌다.</b>
     * 없는 정적 파일을 조용히 404 로 돌리는 것과 같은 이유다.
     *
     * <p><b>응답을 쓰지 않는다.</b> SSE 는 이미 헤더와 일부 내용이 나간 뒤라 여기서 JSON 을
     * 쓰려 하면 그 시도 자체가 실패한다 — 실제로 「Failure in @ExceptionHandler」가 함께
     * 찍혔다. 반환형을 {@code void} 로 두어 응답을 건드리지 않는다.
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncTimeout(AsyncRequestTimeoutException exception,
                                   HttpServletRequest request) {
        log.debug("열어 둔 연결이 시간을 다했습니다 — {} {}",
                request.getMethod(), request.getRequestURI());
    }

    /**
     * 요청이 형식을 갖추지 못한 경우.
     *
     * <p>필요한 값이 빠졌거나(주소를 직접 고쳐 들어온 경우) 숫자 자리에 글자가 온 경우다.
     * <b>서버가 고장난 것이 아니라 요청이 잘못된 것</b>이므로 400 으로 돌려준다.
     *
     * <p>500 으로 두면 두 가지를 잃는다. 사용자는 오타 하나에 「서버 오류」 화면을 보고 자기
     * 잘못인 줄 모르며, 로그에는 링크가 깨지거나 봇이 훑을 때마다 ERROR 가 쌓여 <b>진짜 장애가
     * 그 사이에 묻힌다.</b> {@link NoResourceFoundException} 을 조용히 404 로 돌리는 것과 같은
     * 이유다.
     */
    @ExceptionHandler({MissingServletRequestParameterException.class,
                       MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleMalformedRequest(
            Exception exception, HttpServletRequest request) {

        ErrorCode errorCode = ErrorCode.INVALID_INPUT;
        log.debug("요청 형식 오류 — {} {} ({})", request.getMethod(), request.getRequestURI(),
                exception.getMessage());

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

        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
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
