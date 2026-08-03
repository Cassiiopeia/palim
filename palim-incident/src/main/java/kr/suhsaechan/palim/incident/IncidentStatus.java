package kr.suhsaechan.palim.incident;

/**
 * 인시던트 상태.
 *
 * <p>미확인 → 확인 → 해결의 단방향이다. RESOLVED 는 최종 상태로, 같은 문제가 재발하면
 * 재오픈이 아니라 <b>새 인시던트</b>가 된다 — 지난 건의 해결 이력(누가 언제 어떻게
 * 조치했는지)을 보존하기 위해서다.
 */
public enum IncidentStatus {

    /** 미확인 — 발생 후 아무도 보지 않았다. 화면 기본 탭. */
    OPEN("미확인"),

    /** 확인 — 발주자가 인지했고 조치 중이다. */
    ACKNOWLEDGED("확인"),

    /** 해결 — 사람이 마감했다. 최종 상태. */
    RESOLVED("해결");

    private final String displayName;

    IncidentStatus(String displayName) {
        this.displayName = displayName;
    }

    /** 화면 표시 이름. */
    public String displayName() {
        return displayName;
    }
}
