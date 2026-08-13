package kr.suhsaechan.palim.connector.source.http;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 연동 대상 프리셋.
 *
 * <p><b>흐름과 벤더를 나눠 담는다.</b> 인증 절차({@link AuthFlow})는 몇 가지 유형으로 수렴하지만,
 * 접속 주소·필드 이름 같은 구체적인 값은 시스템마다 다르다. 절차만 남기고 값을 전부 사람에게
 * 입력시키면 "로그인 주소가 뭐죠"부터 막히고, 반대로 값까지 코드에 박아 하나로 만들면 새 시스템이
 * 붙을 때마다 클래스를 늘려야 한다.
 *
 * <p>그래서 프리셋은 <b>흐름 + 그 시스템의 기본값</b>이다. 사용자는 계정 정보만 넣는다.
 * 새 시스템은 여기 한 줄을 더하면 되고, 기본값이 맞지 않으면 화면에서 덮어쓸 수 있다.
 */
public enum ApiAuthPreset {

    /**
     * 이카운트 ERP 오픈 API.
     *
     * <p>회사코드로 지역을 조회한 뒤 인증키로 로그인해 세션을 받는다. 인증키는 발급 시 지정한
     * 사용자 ID 에 묶이므로, 로그인에 쓰는 ID 가 다르면 인증이 거부된다.
     *
     * <p>테스트 환경과 운영 환경의 주소 접두어가 다르다. 테스트용 인증키는 업무 API 를 한 번
     * 성공시키면 소진되므로 검증은 한 번에 끝내야 한다.
     */
    ECOUNT("이카운트 ERP", AuthFlow.ZONE_SESSION,
            "회사코드·사용자ID·API 인증키가 필요합니다. 인증키는 ERP 관리자가 발급합니다.",
            Map.of("apiDomain", "ecount.com",
                    "sandboxPrefix", "sboapi",
                    "livePrefix", "oapi")),

    /**
     * ONEWMS 3자물류 재고 어드민.
     *
     * <p>공개 API 가 유료라 화면이 쓰는 경로를 그대로 쓴다. 로그인 폼을 전송해 세션 쿠키를 받고,
     * 화면이 호출하는 조회 요청을 같은 형식으로 보낸다.
     *
     * <p><b>상대 화면이 바뀌면 깨진다.</b> 수집 실패를 반드시 알려야 한다 — 조용히 멈추면 옛
     * 데이터로 대사가 계속 돌고, 그 결과를 믿고 판단하게 된다.
     */
    ONEWMS("ONEWMS (3자물류)", AuthFlow.FORM_SESSION,
            "회사코드(계정정보)·아이디·비밀번호가 필요합니다. 조회 전용으로만 씁니다.",
            Map.of("loginUrl", "https://svc.onewms.co.kr/login.html",
                    "fetchUrl", "https://svc.onewms.co.kr/function.html",
                    "useridField", "userid",
                    "passwordField", "passwd",
                    "tokenField", "token",
                    "rowsPath", "rows",
                    "fetchBody", "template=I100&action=search&page_code=I100&rows=500&page=1"
                            + "&sidx=&sord=asc&_search=false")),

    /** 위에 없는 시스템. 주소와 필드 이름을 직접 입력한다. */
    CUSTOM_FORM("직접 설정 (웹 로그인 방식)", AuthFlow.FORM_SESSION,
            "로그인 주소·조회 주소·요청 본문을 직접 입력합니다.", Map.of());

    /** 인증 절차 유형. 검증기는 이 값으로 고른다. */
    public enum AuthFlow {
        /** 지역 조회 → 로그인(세션 발급) → 업무 API. */
        ZONE_SESSION,
        /** 로그인 폼 → 세션 쿠키 → 조회. */
        FORM_SESSION
    }

    private final String label;
    private final AuthFlow flow;
    private final String hint;
    private final Map<String, String> defaults;

    ApiAuthPreset(String label, AuthFlow flow, String hint, Map<String, String> defaults) {
        this.label = label;
        this.flow = flow;
        this.hint = hint;
        this.defaults = defaults;
    }

    public String getLabel() {
        return label;
    }

    public AuthFlow getFlow() {
        return flow;
    }

    public String getHint() {
        return hint;
    }

    /** 그 값을 어디서 찾는지. 화면 칸 밑에 그대로 붙는다. */
    public String getAccountHelp() {
        return switch (this) {
            case ECOUNT -> "이카운트 로그인 화면에서 아이디 위에 넣는 6자리 숫자입니다.";
            case ONEWMS -> "ONEWMS 로그인 화면 맨 위 「계정정보」 칸에 넣는 값입니다.";
            case CUSTOM_FORM -> "로그인 폼이 회사·계정 코드를 받는다면 그 값입니다. 없으면 비워 둡니다.";
        };
    }

    /** 비밀값 칸 이름. 시스템마다 넣는 것이 다르다. */
    public String getSecretLabel() {
        return switch (this) {
            case ECOUNT -> "API 인증키";
            case ONEWMS, CUSTOM_FORM -> "비밀번호";
        };
    }

    /** 인증키를 어디서 어떻게 받는지. 모르면 여기서 막힌다. */
    public String getIssueGuide() {
        return switch (this) {
            case ECOUNT -> "이카운트는 관리자가 발급한 인증키가 있어야 합니다. "
                    + "먼저 테스트 인증키로 검증한 뒤 정식 인증키를 받는 순서입니다.";
            case ONEWMS -> "별도 발급이 없습니다. 평소 로그인에 쓰는 계정을 그대로 넣습니다.";
            case CUSTOM_FORM -> "로그인에 쓰는 계정을 넣고, 접속 주소를 직접 지정합니다.";
        };
    }

    /** 이 시스템이 테스트/정식 키를 구분하는가. 구분하지 않으면 그 선택을 감춘다. */
    public boolean hasKeyStages() {
        return this == ECOUNT;
    }

    /**
     * 「회사/계정 코드」 칸에 붙일 이름.
     *
     * <p>같은 자리에 들어가는 값이지만 시스템마다 부르는 말이 다르다. 화면이 상대 시스템에서
     * 쓰는 말을 그대로 보여줘야 사용자가 어디서 가져올 값인지 안다.
     */
    public String getAccountLabel() {
        return switch (this) {
            case ECOUNT -> "회사코드";
            case ONEWMS -> "계정정보 (회사코드)";
            case CUSTOM_FORM -> "계정·회사 코드 (필요할 때만)";
        };
    }

    /**
     * 그 값이 실제로 들어갈 파라미터 이름.
     *
     * <p>지역 조회 방식은 {@code companyCode} 로 지역을 찾고, 폼 로그인 방식은 {@code domain}
     * 으로 로그인 폼에 보낸다. 화면은 칸 하나만 보여주고 여기서 갈라 준다 — 사용자가 두 이름의
     * 차이를 알 이유가 없다.
     */
    public String getAccountParam() {
        return flow == AuthFlow.ZONE_SESSION ? "companyCode" : "domain";
    }

    /** 사용자가 입력한 값이 기본값을 덮는다. 기본값이 맞지 않는 환경에서도 쓸 수 있어야 한다. */
    public Map<String, String> mergeDefaults(Map<String, String> params) {
        Map<String, String> merged = new LinkedHashMap<>(defaults);
        params.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    /** 직접 설정이 필요한가. 화면에서 주소 입력칸을 열지 결정한다. */
    public boolean needsManualEndpoint() {
        return defaults.isEmpty();
    }
}
