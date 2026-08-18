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
 *   <tr><td>{@code X}</td><td>AI 호출</td></tr>
 *   <tr><td>{@code K}</td><td>데이터 연동(커넥터)</td></tr>
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
    AI_DAILY_LIMIT_EXCEEDED("X006", HttpStatus.TOO_MANY_REQUESTS, LogLevel.WARN),

    /** 커넥터를 찾을 수 없다. */
    CONNECTOR_NOT_FOUND("K001", HttpStatus.NOT_FOUND, LogLevel.WARN),

    /** 같은 커넥터가 이미 실행 중이다. cron 과 수동 실행이 겹치는 순간은 반드시 온다. */
    CONNECTOR_ALREADY_RUNNING("K002", HttpStatus.CONFLICT, LogLevel.WARN),

    /** 원천에 접근할 수 없다(파일 없음·API 응답 없음). */
    CONNECTOR_SOURCE_UNREACHABLE("K003", HttpStatus.BAD_GATEWAY, LogLevel.ERROR),

    /**
     * 확정되지 않은 매핑으로 실제 적재를 시도했다.
     *
     * <p>{@code DRAFT} 로도 <b>테스트 실행은 가능하다</b> — 확정 전 검증이 그 목적이다.
     * 실제 데이터를 넣는 것만 막는다. 확정 이력이 없으면 나중에 되돌릴 근거가 없다.
     */
    MAPPING_NOT_ACTIVE("K004", HttpStatus.CONFLICT, LogLevel.WARN),

    /** 매핑 버전을 찾을 수 없다. */
    MAPPING_NOT_FOUND("K005", HttpStatus.NOT_FOUND, LogLevel.WARN),

    /**
     * 원천 양식이 바뀌었다.
     *
     * <p>이 시스템의 최악 실패는 양식이 바뀌었는데 조용히 잘못된 데이터가 들어가는 것이다.
     * 다만 과민하면 사람이 감지를 꺼버리므로, <b>매핑에 실제로 쓰는 필드</b>가 사라졌을 때만
     * 막는다. 컬럼 추가는 경고로 지나간다.
     */
    SCHEMA_DRIFT_DETECTED("K006", HttpStatus.CONFLICT, LogLevel.ERROR),

    /** 필수 목표 필드에 값이 없다. 행 단위 실패이므로 나머지 행은 계속 적재된다. */
    REQUIRED_FIELD_MISSING("K007", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.DEBUG),

    /** 값을 목표 필드 타입으로 변환할 수 없다. */
    FIELD_TYPE_MISMATCH("K008", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.DEBUG),

    /**
     * 단위가 명시됐는데 환산 규칙이 없다.
     *
     * <p>조용히 1:1 로 넘기면 BOX 12개가 EA 12개로 둔갑하고, 그 오류는 대사 결과가
     * 이상해질 때까지 아무도 모른다. 단위가 <b>비어 있는</b> 경우는 실패가 아니다 —
     * 단위 개념이 없는 원천이 흔하다.
     */
    UNIT_CONVERSION_NOT_FOUND("K009", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.WARN),

    /** py 훅 실행이 실패했다. */
    HOOK_EXECUTION_FAILED("K010", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    /** py 훅이 시간 내에 끝나지 않았다. 좀비 프로세스가 쌓이면 서버가 죽는다. */
    HOOK_TIMEOUT("K011", HttpStatus.GATEWAY_TIMEOUT, LogLevel.ERROR),

    /** 사용 중인 목표 모델은 삭제할 수 없다. */
    TARGET_MODEL_IN_USE("K012", HttpStatus.CONFLICT, LogLevel.WARN),

    /** 자연키 구성 필드가 비어 UPSERT 대상을 특정할 수 없다. */
    NATURAL_KEY_INCOMPLETE("K013", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.WARN),

    /**
     * 마지막 실행이 아닌 것을 되돌리려 했다.
     *
     * <p>그 이전까지 거슬러 오르면 이후 실행들과 뒤엉켜 어떤 상태로 돌아가는지 아무도
     * 설명할 수 없게 된다.
     */
    ROLLBACK_NOT_ALLOWED("K014", HttpStatus.CONFLICT, LogLevel.WARN),

    /**
     * 원천 API 인증 흐름이 실패했다. 어느 단계인지는 메시지 인자로 넘긴다.
     *
     * <p>5xx 가 아니라 422 인 이유 — 대부분 회사코드·인증키처럼 <b>사람이 고칠 수 있는 입력</b>
     * 문제다. 5xx 로 두면 서버 장애로 분류되어 ERROR 로 쌓이고, 진짜 장애가 그 사이에 묻힌다.
     */
    API_PROBE_FAILED("K015", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.WARN),

    /** 연결 정의에 필요한 값이 비어 있다. */
    API_PROBE_INCOMPLETE("K016", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.WARN),

    /**
     * 칸을 하나도 잇지 않은 매핑을 확정하려 했다.
     *
     * <p>확정은 <b>되돌리기 어려운 동작</b>이다 — 쓰던 확정판이 내려가고 이것이 그 자리에
     * 올라간다. 이을 칸이 하나도 없으면 그 뒤의 모든 적재가 「필수 칸이 비었다」 로 전 행
     * 실패하는데, 화면상으로는 확정이 정상적으로 끝난 것처럼 보인다.
     *
     * <p>빈 초안은 <b>사람이 만든 것이 아닐 수 있다.</b> 「다시 받아오기」 가 저장된 초안이
     * 없으면 빈 초안을 새로 만들기 때문에, 확정 직후 칸 구조만 갱신해도 이 상태가 된다.
     */
    MAPPING_EMPTY("K017", HttpStatus.CONFLICT, LogLevel.WARN),

    /**
     * 양쪽 재고의 기준 시각이 다르다.
     *
     * <p>실패로 다루는 것이 «막다른 길» 이 아니다. 기준일을 지정해 다시 받아올 수 있으므로,
     * 사람이 할 일이 분명히 있다.
     */
    RECONCILE_BASE_AT_MISMATCH("R001", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.WARN),

    /** 비교할 재고가 한쪽에 아예 없다. 수집이 안 돌았거나 기준일이 어긋난 것이다. */
    RECONCILE_SNAPSHOT_MISSING("R002", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.WARN),

    /** 아무것도 담지 않고 「잇기」 를 눌렀다. */
    RECONCILE_LINK_EMPTY("R003", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    /**
     * 서로 다른 두 물건에 속한 품목을 함께 이으려 했다.
     *
     * <p>그것은 「두 물건을 합치는」 일이다. 어느 이름을 남길지·수량을 어떻게 볼지가 사람의
     * 판단이라 조용히 정해 버리면 안 된다.
     */
    RECONCILE_LINK_TWO_UNITS("R004", HttpStatus.CONFLICT, LogLevel.DEBUG),

    /**
     * 한쪽에만 있는 줄을 그대로 이으려 했다.
     *
     * <p>막는 이유가 있다. 한쪽 품목만 든 물건은 합산이 「좌 120 · 우 0」 이 되어 <b>대조가
     * 매일 전량 차이를 올린다.</b> 사람은 그것을 매칭 문제가 아니라 재고 사고로 읽고, 원인이
     * 여기 있다는 것을 알 방법이 없다.
     *
     * <p>짝이 정말 없는 품목은 「짝 없음으로 두기」 가 제 자리다.
     */
    RECONCILE_LINK_ONE_SIDED("R005", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.DEBUG),

    /** 정규식이 잘못됐다. 저장하면 그 규칙이 조용히 건너뛰어져 매칭이 이유 없이 줄어든다. */
    NORMALIZATION_RULE_INVALID("R006", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.DEBUG),

    /**
     * 미리보기가 제한 시간을 넘겼다.
     *
     * <p>사람이 정규식을 직접 넣는 화면이라 <b>되돌아가는 패턴</b>이 들어올 수 있다.
     * {@code (a+)+$} 같은 것 하나로 요청 스레드가 영원히 돌 수 있으므로 시간을 끊는다.
     */
    NORMALIZATION_PREVIEW_TIMEOUT("R007", HttpStatus.UNPROCESSABLE_ENTITY, LogLevel.WARN);

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
