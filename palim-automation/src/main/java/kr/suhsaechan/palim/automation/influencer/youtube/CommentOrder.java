package kr.suhsaechan.palim.automation.influencer.youtube;

/**
 * 댓글 정렬.
 *
 * <p>두 정렬을 모두 수집한다. {@link #TIME} 은 <b>지금 벌어지는 일</b>을 보여준다 — 논란이 터지면
 * 몇 시간 안에 최신 댓글로 몰리므로 브랜드 안전성 탐지의 핵심 신호다. {@link #RELEVANCE} 는
 * 커뮤니티가 공유하는 인식을 보여준다.
 */
public enum CommentOrder {

    /** 최신순 — API 파라미터 {@code order=time}. */
    TIME("time"),

    /** 인기순 — API 파라미터 {@code order=relevance}. */
    RELEVANCE("relevance");

    private final String parameter;

    CommentOrder(String parameter) {
        this.parameter = parameter;
    }

    public String parameter() {
        return parameter;
    }
}
