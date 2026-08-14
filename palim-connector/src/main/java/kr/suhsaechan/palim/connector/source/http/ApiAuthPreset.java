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
     * <p><b>로그인이 평범하지 않다.</b> 입력값을 그대로 보내는 것이 아니라, 로그인 화면에 실려
     * 오는 공개키로 폼 전체를 암호화해 한 칸({@code encpar})에 담아 별도 주소로 보낸다. 평범한
     * 폼 전송으로는 상대가 200 과 함께 「연결에 실패하였습니다」 를 돌려주고 세션을 주지 않는다 —
     * 그 화면만 봐서는 계정을 의심하게 되므로 여기 적어 둔다.
     *
     * <p><b>상대 화면이 바뀌면 깨진다.</b> 수집 실패를 반드시 알려야 한다 — 조용히 멈추면 옛
     * 데이터로 대사가 계속 돌고, 그 결과를 믿고 판단하게 된다.
     */
    ONEWMS("ONEWMS (3자물류)", AuthFlow.FORM_SESSION,
            "회사코드(계정정보)·아이디·비밀번호가 필요합니다. 조회 전용으로만 씁니다.",
            Map.ofEntries(
                    Map.entry("loginUrl", "https://svc.onewms.co.kr/login.html"),
                    // 암호화한 로그인 값을 받는 주소. 로그인 화면과 다르다.
                    Map.entry("loginProcessUrl", "https://svc.onewms.co.kr/login_process.php"),
                    Map.entry("fetchUrl", "https://svc.onewms.co.kr/function.html"),
                    Map.entry("useridField", "userid"),
                    Map.entry("passwordField", "passwd"),
                    Map.entry("tokenField", "token"),
                    Map.entry("encryptField", "encpar"),
                    // 화면이 매번 새로 만들어 보내는 창 식별자. 없으면 거부당한다.
                    Map.entry("sessionIdField", "tab_id"),
                    Map.entry("rowsPath", "rows"),
                    // 상대가 알려주는 전체 건수. 받은 행이 이보다 적으면 잘린 것이다.
                    Map.entry("recordsPath", "records"),
                    // par 는 검색조건 묶음이고 nd 는 캐시 무력화용 현재 시각이다.
                    // 둘 다 없으면 화면이 보내는 요청과 달라져 응답 모양이 바뀐다.
                    Map.entry("fetchBody", "template=I100&action=search&page_code=I100"
                            + "&rows=500&page=1&sidx=&sord=asc&_search=false&nd={nd}"
                            + "&par=stock_warehouse%3D1%26stock_stock_type%3D0"
                            + "%26select_field%3DI100%26products_sort%3D1"))),

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

    /**
     * 칸에 흐리게 적어 두는 예시.
     *
     * <p>이카운트 회사코드는 숫자 여섯 자리지만 다른 시스템은 그렇지 않다. 예시를 한 시스템에
     * 맞춰 고정해 두면, 다른 시스템을 고른 사람은 <b>자기 값이 틀린 줄 안다.</b>
     */
    public String getAccountPlaceholder() {
        return switch (this) {
            case ECOUNT -> "숫자 6자리";
            case ONEWMS -> "로그인 화면 맨 위에 넣는 값";
            case CUSTOM_FORM -> "없으면 비워 둡니다";
        };
    }

    public String getSecretPlaceholder() {
        return switch (this) {
            case ECOUNT -> "발급받은 인증키를 붙여 넣으세요";
            case ONEWMS, CUSTOM_FORM -> "로그인에 쓰는 비밀번호";
        };
    }

    /**
     * 접어 둔 안내의 제목.
     *
     * <p>「인증키가 없으신가요?」 는 인증키를 쓰는 시스템에만 맞는 말이다. 평소 계정으로 붙는
     * 시스템에 그 제목을 띄우면, 받을 필요가 없는 것을 받으러 간다.
     */
    public String getCredentialGuideTitle() {
        return switch (this) {
            case ECOUNT -> "인증키가 없으신가요? — 발급 방법 보기";
            case ONEWMS, CUSTOM_FORM -> "무엇을 넣어야 하나요?";
        };
    }

    /**
     * 이 화면에서 지금 무엇을 하는 중인지.
     *
     * <p>시스템마다 절차가 다르다. 인증키를 두 번 받는 곳이 있고, 평소 계정을 그대로 쓰는 곳이
     * 있다. 한쪽 문구를 고정해 두면 나머지 시스템을 고른 사람은 <b>있지도 않은 인증키를
     * 찾으러 간다.</b>
     */
    public String getIntro() {
        return switch (this) {
            case ECOUNT -> "테스트 인증키로 먼저 확인합니다. "
                    + "확인이 끝나면 저장되고, 칸 맞추기를 마친 뒤 정식 인증키로 바꾸면 "
                    + "매일 자동으로 가져옵니다.";
            case ONEWMS -> "평소 로그인에 쓰는 계정으로 확인합니다. "
                    + "확인이 끝나면 저장되고, 칸 맞추기를 마치면 매일 자동으로 가져옵니다.";
            case CUSTOM_FORM -> "로그인에 쓰는 계정과 접속 주소로 확인합니다. "
                    + "확인이 끝나면 저장되고, 칸 맞추기를 마치면 매일 자동으로 가져옵니다.";
        };
    }

    /** 인증키를 어디서 어떻게 받는지. 모르면 여기서 막힌다. */
    public String getIssueGuide() {
        return switch (this) {
            case ECOUNT -> "이카운트는 관리자가 발급한 인증키가 있어야 합니다. "
                    + "먼저 테스트 인증키로 검증한 뒤 정식 인증키를 받는 순서입니다. "
                    + "인증키와 별개로 「API인증키발급 > IP등록」에 이 서버의 주소를 등록해야 "
                    + "합니다 — 등록하지 않으면 키가 맞아도 로그인에서 막힙니다.";
            case ONEWMS -> "별도 발급이 없습니다. 평소 로그인에 쓰는 계정을 그대로 넣습니다.";
            case CUSTOM_FORM -> "로그인에 쓰는 계정을 넣고, 접속 주소를 직접 지정합니다.";
        };
    }

    /** 이 시스템이 테스트/정식 키를 구분하는가. 구분하지 않으면 그 선택을 감춘다. */
    public boolean hasKeyStages() {
        return this == ECOUNT;
    }

    /**
     * 접속 IP 를 <b>미리 등록해야</b> 열어 주는 시스템의 안내. 해당 없으면 빈 문자열.
     *
     * <p>키만 받으면 될 것 같지만, 상대가 "어느 서버에서 부르는지"까지 등록해야 여는 경우가
     * 있다. 화면이 이것을 미리 말해 주지 않으면 키가 맞는데도 로그인에서 막히고, 그 화면만
     * 봐서는 키를 의심하게 되어 멀쩡한 키를 재발급받는 데 시간을 쓴다.
     */
    public String getIpAllowlistGuide() {
        return switch (this) {
            case ECOUNT -> "이카운트에서 「Self-Customizing > 정보관리 > API인증키발급 > IP등록」"
                    + " 에 이 서버의 주소를 추가하세요.";
            case ONEWMS, CUSTOM_FORM -> "";
        };
    }

    /** 화면이 IP 등록 안내를 띄울지 결정한다. */
    public boolean needsIpAllowlist() {
        return !getIpAllowlistGuide().isEmpty();
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
