package kr.suhsaechan.palim.connector.define;

/**
 * 연결 상태.
 *
 * <p>이 값이 있어야 화면이 <b>다음에 무엇을 하라고</b> 말할 수 있다. 상태를 모르면 사용자는
 * 등록을 마친 뒤 "이제 뭘 해야 하지"에서 멈춘다.
 */
public enum ConnectionStatus {

    /** 아직 인증정보를 넣지 않았다. */
    NOT_CONFIGURED("설정 필요", "인증정보를 등록하세요"),

    /**
     * 테스트 키로 검증을 통과했다.
     *
     * <p><b>여기서 끝이 아니다.</b> 테스트 키는 업무 API 를 한 번 성공시키면 소진되는 경우가
     * 있어, 이 상태로 매일 수집을 돌릴 수 없다. 화면이 정식 키 교체를 안내해야 한다 —
     * 안 그러면 사용자는 검증에 성공했으니 끝난 줄 알고 있다가 첫 수집에서 막힌다.
     */
    VERIFIED_TEST("테스트 검증됨", "정식 인증키로 교체하세요"),

    /** 정식 키로 검증을 통과했다. 수집을 돌릴 수 있다. */
    VERIFIED_LIVE("연결됨", null),

    /**
     * 검증에 실패했거나, 되던 것이 멈췄다.
     *
     * <p>키 만료가 흔한 원인이다. 배치가 조용히 죽는 대표 경로라 상태로 남겨 눈에 띄게 한다.
     */
    FAILED("연결 실패", "인증정보를 다시 확인하세요");

    private final String label;
    private final String nextAction;

    ConnectionStatus(String label, String nextAction) {
        this.label = label;
        this.nextAction = nextAction;
    }

    public String getLabel() {
        return label;
    }

    /** 사용자가 다음에 할 일. 없으면 이 단계는 끝난 것이다. */
    public String getNextAction() {
        return nextAction;
    }

    public boolean isUsable() {
        return this == VERIFIED_LIVE;
    }

    public boolean needsAttention() {
        return this == NOT_CONFIGURED || this == FAILED || this == VERIFIED_TEST;
    }
}
