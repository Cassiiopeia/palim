package kr.suhsaechan.palim.web.influencer;

/**
 * 등급표 정렬.
 *
 * <p>두 정렬이 <b>서로 다른 채널을 1위로 올린다</b>는 점이 핵심이다. 총점 1위가 예산 대비로는
 * 최악일 수 있고, 그 사실은 CPV 로 정렬해 봐야만 드러난다.
 */
public enum GradeSort {

    /** 총점순 — 종합 품질. */
    TOTAL("총점순"),

    /** CPV 효율순 — 같은 돈으로 더 많이 도달하는 순서. 실무에서 실제로 쓰는 정렬이다. */
    CPV("가성비순");

    private final String displayName;

    GradeSort(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
