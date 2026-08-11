package kr.suhsaechan.palim.automation.influencer.domain;

/**
 * 내부 심사 판정.
 *
 * <p>AI 는 이 값을 정하지 않는다. 근거를 제시할 뿐이고 판단은 사람이 한다 — 광고 캐스팅은
 * 실패 비용이 크고, 인플루언서에 대한 판단은 사람의 평판에 영향을 주기 때문이다.
 */
public enum ReviewDecision {

    /** 제안 대상. DM 초안 생성의 입력이 된다. */
    PROPOSE("제안", "badge-success"),

    /** 보류 — 지금은 아니지만 후보로 남긴다. 확인이 필요한 신호가 있을 때. */
    HOLD("보류", "badge-warning"),

    /** 제외 — 이 캠페인에는 맞지 않는다. 목록에서 내린다. */
    EXCLUDE("제외", "badge-ghost");

    private final String displayName;
    private final String badgeClass;

    ReviewDecision(String displayName, String badgeClass) {
        this.displayName = displayName;
        this.badgeClass = badgeClass;
    }

    public String displayName() {
        return displayName;
    }

    public String badgeClass() {
        return badgeClass;
    }
}
