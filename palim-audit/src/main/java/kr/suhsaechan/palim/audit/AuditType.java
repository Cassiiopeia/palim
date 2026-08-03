package kr.suhsaechan.palim.audit;

/**
 * 감사 로그 유형.
 *
 * <h2>유형을 자유 문자열로 두지 않는 이유</h2>
 *
 * <p>화면에서 유형별 다중 선택 필터를 제공한다. 문자열이면 오타가 섞인 값이 쌓여 필터에
 * 걸리지 않는 행이 생기고, <b>감사 로그에서 누락은 곧 감사 실패</b>다.
 *
 * <p>{@link #group()} 은 화면 필터를 인증 / 조회 / 변경으로 묶는 데 쓴다. 유형이 늘어날 때
 * 화면 코드를 고치지 않아도 되도록 유형 자신이 소속을 안다.
 */
public enum AuditType {

    // --- 인증 ---
    /** 로그인 성공. */
    LOGIN_SUCCESS("로그인", "로그인했습니다.(인증 성공)", AuditGroup.AUTH),

    /** 비밀번호 불일치 또는 없는 계정. */
    LOGIN_FAILURE("로그인 실패", "로그인 실패했습니다.(인증 실패)", AuditGroup.AUTH),

    /** 이미 접속 중인 계정이라 로그인을 보류했다. */
    LOGIN_BLOCKED_DUPLICATE("로그인 실패", "로그인 실패했습니다.(로그인 중복)", AuditGroup.AUTH),

    /** 잠긴 계정으로 로그인을 시도했다. */
    LOGIN_BLOCKED_LOCKED("로그인 실패", "로그인 실패했습니다.(계정 잠김)", AuditGroup.AUTH),

    /** 허용되지 않은 IP 에서 로그인을 시도했다. */
    LOGIN_BLOCKED_IP("로그인 실패", "로그인 실패했습니다.(허용되지 않은 IP)", AuditGroup.AUTH),

    /** 실패 누적으로 계정이 잠겼다. */
    ACCOUNT_LOCKED("계정 잠금", "로그인 실패 누적으로 계정이 잠겼습니다.", AuditGroup.AUTH),

    /** 스스로 로그아웃했다. */
    LOGOUT("로그아웃", "로그아웃했습니다.", AuditGroup.AUTH),

    /** 같은 계정의 새 로그인 때문에 강제 종료됐다. */
    LOGOUT_DUPLICATE("로그아웃", "로그아웃했습니다.(로그인 중복)", AuditGroup.AUTH),

    /** 유휴 시간 초과로 세션이 만료됐다. */
    SESSION_EXPIRED("세션 만료", "세션이 만료됐습니다.", AuditGroup.AUTH),

    /** 비밀번호를 변경했다. */
    PASSWORD_CHANGE("비밀번호 변경", "비밀번호를 변경했습니다.", AuditGroup.AUTH),

    /** 비밀번호 변경에 실패했다(현재 비밀번호 불일치·정책 위반). */
    PASSWORD_CHANGE_FAILED("비밀번호 변경 실패", "비밀번호 변경에 실패했습니다.", AuditGroup.AUTH),

    // --- 조회 ---
    /** 화면 조회. 인터셉터가 자동 기록한다. */
    VIEW("조회", "조회했습니다.", AuditGroup.VIEW),

    // --- 변경 ---
    SKU_CREATE("재고", "SKU 를 등록했습니다.", AuditGroup.CHANGE),
    SKU_UPDATE("재고", "SKU 정보를 변경했습니다.", AuditGroup.CHANGE),
    SKU_DISCONTINUE("재고", "SKU 를 단종 처리했습니다.", AuditGroup.CHANGE),
    STOCK_ADJUST("재고", "재고를 조정했습니다.", AuditGroup.CHANGE),

    MAPPING_CONNECT("상품 매핑", "매핑을 등록했습니다.", AuditGroup.CHANGE),
    MAPPING_RECONNECT("상품 매핑", "매핑 대상을 변경했습니다.", AuditGroup.CHANGE),
    MAPPING_DEACTIVATE("상품 매핑", "매핑을 해제했습니다.", AuditGroup.CHANGE),

    CHANNEL_CREDENTIAL_UPDATE("채널 설정", "채널 인증정보를 변경했습니다.", AuditGroup.CHANGE),
    CHANNEL_TOGGLE("채널 설정", "채널 사용 여부를 변경했습니다.", AuditGroup.CHANGE),

    NOTIFICATION_SETTING_UPDATE("알림 설정", "알림 설정을 변경했습니다.", AuditGroup.CHANGE),

    NOTIFICATION_RESEND("알림 재발송", "실패한 알림을 재발송 대기로 되돌렸습니다.", AuditGroup.CHANGE),

    INCIDENT_ACKNOWLEDGE("인시던트", "인시던트를 확인했습니다.", AuditGroup.CHANGE),
    INCIDENT_RESOLVE("인시던트", "인시던트를 해결했습니다.", AuditGroup.CHANGE);

    private final String displayName;
    private final String defaultSummary;
    private final AuditGroup group;

    AuditType(String displayName, String defaultSummary, AuditGroup group) {
        this.displayName = displayName;
        this.defaultSummary = defaultSummary;
        this.group = group;
    }

    /** 화면 "유형" 열에 표시하는 값. 여러 유형이 같은 이름을 공유할 수 있다. */
    public String displayName() {
        return displayName;
    }

    /** 화면 "내용" 열의 기본 문장. 대상이 있으면 호출부가 앞에 붙인다. */
    public String defaultSummary() {
        return defaultSummary;
    }

    public AuditGroup group() {
        return group;
    }

    /** 인증 실패 계열인지. 실패는 성공보다 오래 보존할 수 있어야 한다. */
    public boolean isAuthFailure() {
        return this == LOGIN_FAILURE
                || this == LOGIN_BLOCKED_DUPLICATE
                || this == LOGIN_BLOCKED_LOCKED
                || this == LOGIN_BLOCKED_IP
                || this == ACCOUNT_LOCKED
                || this == PASSWORD_CHANGE_FAILED;
    }

    public enum AuditGroup {
        AUTH("인증"),
        VIEW("조회"),
        CHANGE("변경");

        private final String displayName;

        AuditGroup(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }
}
