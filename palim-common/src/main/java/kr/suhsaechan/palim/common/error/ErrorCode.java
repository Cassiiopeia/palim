package kr.suhsaechan.palim.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.logging.LogLevel;
import org.springframework.http.HttpStatus;

/**
 * 실패 유형 식별자.
 *
 * <p>모든 실패가 이 하나의 enum 에 모인다. 도메인별로 인터페이스 구현체를 나누는 방식도
 * 가능하지만, 그렇게 하면 <b>새 코드를 추가할 때 손댈 곳이 오히려 늘어난다</b> — enum 파일,
 * 메시지 파일, 검증 테스트 등록, 메시지 basename 까지 네 곳이다. 여기서는 enum 한 줄과
 * 메시지 두 줄이면 끝난다.
 *
 * <p>더 중요한 것은 검증이다. 한 enum 에 모여 있으면 {@code values()} 로 전체를 순회해
 * 코드 중복과 메시지 누락을 자동으로 잡을 수 있다. 구현체가 흩어져 있으면 검증 테스트에
 * 클래스를 손으로 등록해야 하고, 빠뜨리면 검증에 구멍이 생긴다.
 *
 * <h2>구성</h2>
 *
 * <p>{@link #name()} 은 <b>클라이언트가 분기에 쓰는 식별자</b>다. 메시지 문자열로 분기하면
 * 문구가 바뀔 때 깨지고 다국어에서는 아예 불가능하다.
 *
 * <p>{@link #code()} 는 도메인 접두사 + 세 자리 숫자다.
 *
 * <table border="1">
 *   <caption>접두사</caption>
 *   <tr><td>{@code C}</td><td>공통</td></tr>
 *   <tr><td>{@code S}</td><td>SKU · 재고</td></tr>
 *   <tr><td>{@code O}</td><td>주문</td></tr>
 *   <tr><td>{@code M}</td><td>상품 매핑</td></tr>
 *   <tr><td>{@code H}</td><td>채널</td></tr>
 *   <tr><td>{@code N}</td><td>알림</td></tr>
 *   <tr><td>{@code A}</td><td>인증</td></tr>
 *   <tr><td>{@code I}</td><td>인시던트</td></tr>
 *   <tr><td>{@code Y}</td><td>인플루언서 · 유튜브</td></tr>
 * </table>
 *
 * <p>{@link #logLevel()} 을 코드가 직접 갖는 이유는, 전역 핸들러가 예외마다 if 분기로 레벨을
 * 정하면 새 코드가 추가될 때마다 핸들러를 고쳐야 하기 때문이다. 대표 사례가
 * {@link #ORDER_LINE_DUPLICATE} 다 — 중복 수집은 정상 흐름 제어이므로 경고로 남으면 안 된다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ==================================================================
    // 공통 (C)
    // ==================================================================

    /** 예상하지 못한 실패. 응답에 내부 메시지를 노출하지 않는다. */
    INTERNAL_ERROR("C001", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    INVALID_INPUT("C002", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    /** Bean Validation 실패. 필드별 오류는 details 에 담긴다. */
    VALIDATION_FAILED("C003", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    UNAUTHORIZED("C004", HttpStatus.UNAUTHORIZED, LogLevel.DEBUG),

    ACCESS_DENIED("C005", HttpStatus.FORBIDDEN, LogLevel.WARN),

    /** 트랜잭션 없이 변경 서비스를 호출했다. 설계 위반이므로 반드시 고쳐야 한다. */
    TRANSACTION_REQUIRED("C006", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    /**
     * 설정 키가 등록되어 있지 않다.
     *
     * <p>정의({@code ConfigDefinitionProvider})에 없는 키를 읽었다는 뜻이므로 코드 결함이다.
     */
    CONFIG_NOT_FOUND("C007", HttpStatus.NOT_FOUND, LogLevel.ERROR),

    /** 설정값 형식이 타입과 맞지 않는다. 화면 입력 오류이므로 사용자에게 되돌린다. */
    CONFIG_VALUE_INVALID("C008", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    /** 설정값이 허용 범위를 벗어났다. 배점에 음수가 들어가면 점수 체계가 무너진다. */
    CONFIG_VALUE_OUT_OF_RANGE("C009", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    /** 화면에서 편집할 수 없는 내부 설정을 바꾸려 했다. */
    CONFIG_NOT_EDITABLE("C010", HttpStatus.FORBIDDEN, LogLevel.WARN),

    // ==================================================================
    // SKU · 재고 (S)
    // ==================================================================

    SKU_NOT_FOUND("S001", HttpStatus.NOT_FOUND, LogLevel.WARN),

    SKU_CODE_DUPLICATE("S002", HttpStatus.CONFLICT, LogLevel.DEBUG),

    /**
     * 수동 차감에서 실재고를 초과했다.
     *
     * <p>판매 차감은 오버셀링을 허용하므로 이 코드를 쓰지 않는다. 폐기·분실처럼 사람이
     * 입력하는 경로에서만 발생하며 입력 실수일 가능성이 높다.
     */
    INSUFFICIENT_STOCK("S003", HttpStatus.CONFLICT, LogLevel.WARN),

    INVALID_STOCK_AMOUNT("S004", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    INVALID_SAFETY_THRESHOLD("S005", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    // ==================================================================
    // 주문 (O)
    // ==================================================================

    ORDER_NOT_FOUND("O001", HttpStatus.NOT_FOUND, LogLevel.WARN),

    ORDER_LINE_NOT_FOUND("O002", HttpStatus.NOT_FOUND, LogLevel.WARN),

    /**
     * 이미 수집된 주문 항목이다.
     *
     * <p><b>오류가 아니라 정상 흐름 제어다.</b> 수집 커서는 구간을 겹쳐 조회하므로(설계서 5.4)
     * 같은 주문이 반복 수집되는 것이 정상이며, 이 코드는 "이미 처리했으니 재고를 차감하지 말라"는
     * 신호다. 그래서 로그 레벨이 DEBUG 다 — WARN 이면 정상 동작이 경고 로그를 가득 채운다.
     *
     * <p>이 예외가 발생하면 데이터베이스 트랜잭션은 rollback-only 가 된다. 따라서 수집 조율은
     * 주문 1건 단위로 트랜잭션을 열어야 한다.
     */
    ORDER_LINE_DUPLICATE("O003", HttpStatus.CONFLICT, LogLevel.DEBUG),

    INVALID_ORDER_QUANTITY("O004", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    ORDER_SKU_ID_REQUIRED("O005", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    // ==================================================================
    // 상품 매핑 (M)
    // ==================================================================

    PRODUCT_MAPPING_NOT_FOUND("M001", HttpStatus.NOT_FOUND, LogLevel.WARN),

    PRODUCT_MAPPING_DUPLICATE("M002", HttpStatus.CONFLICT, LogLevel.DEBUG),

    MAPPING_SKU_ID_REQUIRED("M003", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    // ==================================================================
    // 채널 (H)
    // ==================================================================

    CHANNEL_NOT_FOUND("H001", HttpStatus.NOT_FOUND, LogLevel.WARN),

    CHANNEL_CREDENTIAL_NOT_FOUND("H002", HttpStatus.NOT_FOUND, LogLevel.WARN),

    CREDENTIAL_ENCRYPT_FAILED("H003", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    /**
     * 복호화 실패 — 마스터키가 교체되었거나 암호문이 손상됐다.
     *
     * <p>해당 채널의 수집이 즉시 중단되므로 텔레그램 경고 대상이다.
     */
    CREDENTIAL_DECRYPT_FAILED("H004", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    /** 부트스트랩이 수행되지 않았다. 설정 없이 운영에 들어간 상태이므로 즉시 조치해야 한다. */
    STOCK_PUSH_SETTING_NOT_INITIALIZED("H005", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    INVALID_COLLECT_INTERVAL("H006", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    INVALID_MAX_DELTA("H007", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    /** 채널 API 호출 실패. 수집 커서를 전진시키지 않고 다음 주기에 재시도한다. */
    CHANNEL_API_FAILED("H008", HttpStatus.BAD_GATEWAY, LogLevel.ERROR),

    CHANNEL_ADAPTER_NOT_AVAILABLE("H009", HttpStatus.NOT_IMPLEMENTED, LogLevel.DEBUG),

    // ==================================================================
    // 알림 (N)
    // ==================================================================

    NOTIFICATION_NOT_FOUND("N001", HttpStatus.NOT_FOUND, LogLevel.WARN),

    /** 부트스트랩이 수행되지 않았다. 알림이 발송되지 않는 상태이므로 즉시 조치해야 한다. */
    NOTIFICATION_SETTING_NOT_INITIALIZED("N002", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    PAYLOAD_SERIALIZE_FAILED("N003", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    PAYLOAD_DESERIALIZE_FAILED("N004", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    INVALID_BATCH_INTERVAL("N005", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    INVALID_QUIET_HOURS("N006", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    INVALID_REPEAT_HOURS("N007", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    /** 텔레그램 발송 실패. Outbox 에 남아 재시도된다. */
    TELEGRAM_SEND_FAILED("N008", HttpStatus.BAD_GATEWAY, LogLevel.ERROR),

    /** 실패 상태가 아닌 알림에 재발송을 요청했다. */
    NOTIFICATION_NOT_RETRYABLE("N009", HttpStatus.CONFLICT, LogLevel.WARN),

    // ==================================================================
    // 인증 (A)
    // ==================================================================

    ADMIN_ACCOUNT_NOT_FOUND("A001", HttpStatus.NOT_FOUND, LogLevel.WARN),

    INVALID_USERNAME("A002", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    INVALID_PASSWORD("A003", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    /** 계정도 없고 초기 비밀번호도 지정되지 않아 기동할 수 없다. */
    ADMIN_PASSWORD_REQUIRED("A004", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    /** 비밀번호가 정책(최소 길이)에 미달한다. */
    PASSWORD_TOO_SHORT("A005", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    /** 비밀번호에 아이디가 포함되어 있다. */
    PASSWORD_CONTAINS_USERNAME("A006", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    /** 같은 문자가 연속 반복된다. */
    PASSWORD_REPEATED_CHARS("A007", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    /** 현재 비밀번호가 일치하지 않는다. 변경은 현재 비밀번호 재확인을 요구한다. */
    PASSWORD_MISMATCH("A008", HttpStatus.BAD_REQUEST, LogLevel.WARN),

    // ==================================================================
    // 인시던트 (I)
    // ==================================================================

    INCIDENT_NOT_FOUND("I001", HttpStatus.NOT_FOUND, LogLevel.WARN),

    /** 현재 상태에서 허용되지 않는 전이다 — 해결된 건 재해결, 확인된 건 재확인 등. */
    INCIDENT_STATUS_INVALID("I002", HttpStatus.CONFLICT, LogLevel.WARN),

    // ==================================================================
    // 인플루언서 · 유튜브 (Y)
    // ==================================================================

    /** 일일 quota 소진. 오류가 아니라 정상 흐름 제어 — 커서를 저장하고 다음 실행에 재개한다. */
    YOUTUBE_QUOTA_EXCEEDED("Y001", HttpStatus.TOO_MANY_REQUESTS, LogLevel.INFO),

    /** YouTube API 호출 실패. 커서를 전진시키지 않고 다음 주기에 재시도한다. */
    YOUTUBE_API_FAILED("Y002", HttpStatus.BAD_GATEWAY, LogLevel.ERROR),

    /**
     * 자막 수집 실패·차단. 메타+댓글 폴백으로 심사를 계속하는 예상된 상황이므로
     * 5xx 가 아니라 404(자막이라는 리소스가 없음) + WARN 이다. 3회 연속 차단 시 별도 경고.
     */
    TRANSCRIPT_UNAVAILABLE("Y003", HttpStatus.NOT_FOUND, LogLevel.WARN),

    INFLUENCER_CHANNEL_NOT_FOUND("Y004", HttpStatus.NOT_FOUND, LogLevel.WARN),

    INFLUENCER_CAMPAIGN_NOT_FOUND("Y005", HttpStatus.NOT_FOUND, LogLevel.WARN),

    // ==================================================================
    // AI (X)
    // ==================================================================

    /**
     * API 키가 없다. 기동은 되되 AI 기능만 동작하지 않는다.
     *
     * <p>ERROR 로 남기는 이유는 <b>운영자가 조치해야 끝나는 상태</b>이기 때문이다. 심사는 수동
     * 트리거라 이 로그가 쌓여 다른 기록을 묻을 일도 없다.
     */
    AI_NOT_CONFIGURED("X001", HttpStatus.SERVICE_UNAVAILABLE, LogLevel.ERROR),

    /** AI 호출 실패. 재시도 1회 후 해당 항목은 미평가로 남긴다. */
    AI_CALL_FAILED("X002", HttpStatus.BAD_GATEWAY, LogLevel.ERROR),

    /** 구조화 출력이 스키마를 벗어났다. 자유 텍스트 파싱으로 넘어가지 않는다. */
    AI_RESPONSE_INVALID("X003", HttpStatus.BAD_GATEWAY, LogLevel.ERROR),

    /** 프롬프트 리소스를 찾을 수 없다 — 버전 설정과 파일이 어긋난 상태다. */
    AI_PROMPT_NOT_FOUND("X004", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    /**
     * 재실행 쿨다운 중이다.
     *
     * <p>오류가 아니라 의도된 차단이다 — 버튼 연타로 같은 심사가 반복되는 것을 막는다.
     */
    AI_RATE_LIMITED("X005", HttpStatus.TOO_MANY_REQUESTS, LogLevel.DEBUG),

    /** 일일 호출 상한에 도달했다. 비용의 마지막 방어선이므로 넘기지 않는다. */
    AI_DAILY_LIMIT_EXCEEDED("X006", HttpStatus.TOO_MANY_REQUESTS, LogLevel.WARN);

    private final String code;
    private final HttpStatus httpStatus;
    private final LogLevel logLevel;

    public String code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public LogLevel logLevel() {
        return logLevel;
    }

    /** 메시지 프로퍼티 키. */
    public String messageKey() {
        return "error." + name();
    }
}
