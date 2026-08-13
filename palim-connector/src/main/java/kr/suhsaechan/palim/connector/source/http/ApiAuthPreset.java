package kr.suhsaechan.palim.connector.source.http;

/**
 * 외부 시스템 인증 흐름 프리셋.
 *
 * <p>인증은 시스템마다 절차가 다르다. 어떤 곳은 키 하나를 헤더에 넣으면 끝이고, 어떤 곳은
 * 지역(zone)을 먼저 조회한 뒤 로그인해 세션을 받아야 한다. 이 차이를 화면 설정만으로 흡수하려
 * 하면 "요청을 만드는 작은 프로그래밍 언어"를 만들게 되고, 그건 설정이 아니라 코드다.
 *
 * <p>그래서 <b>절차는 프리셋으로 두고, 그 안에서 바뀌는 값만 설정으로 받는다.</b> 새 시스템은
 * {@link ApiProbe} 구현체 하나를 추가하면 붙는다 — 화면과 저장 구조는 손대지 않는다.
 */
public enum ApiAuthPreset {

    /**
     * 지역 조회 → 로그인(세션 발급) → 업무 API 3단계.
     *
     * <p>세션이 만료되면 로그인부터 다시 한다. 인증키는 특정 사용자 ID 에 묶여 있어, 로그인에
     * 쓰는 ID 가 발급 시 지정한 ID 와 다르면 인증이 거부된다.
     */
    ZONE_SESSION("지역 조회 → 로그인 → 조회 (3단계)"),

    /**
     * 로그인 폼 전송 → 세션 쿠키 → 조회.
     *
     * <p>웹 화면이 쓰는 것과 같은 경로다. 공개 API 가 유료이거나 없을 때 쓴다. 화면 구조가
     * 바뀌면 깨지므로 수집 실패를 반드시 알려야 한다 — 조용히 멈추면 옛 데이터로 대사가 돈다.
     */
    FORM_SESSION("로그인 폼 → 세션 쿠키 → 조회");

    private final String description;

    ApiAuthPreset(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
