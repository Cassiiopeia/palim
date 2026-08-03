package kr.suhsaechan.palim.incident;

/**
 * 인시던트 처리 상태 (#34).
 *
 * <p>{@code OPEN → ACKNOWLEDGED → RESOLVED} 단방향이며 {@code OPEN → RESOLVED} 직행을
 * 허용한다. RESOLVED 는 최종 상태다 — 되돌리기가 없고, 재발은 새 인시던트로 만든다.
 * 해결 이력을 덮어쓰기 시작하면 "언제 무엇을 해결했는지"가 사라진다.
 */
public enum IncidentStatus {

    /** 미확인. 발주자가 아직 보지 않았다. */
    OPEN("미확인"),

    /** 확인됨. 봤고 처리 중이다. */
    ACKNOWLEDGED("확인"),

    /** 해결됨. 최종 상태. */
    RESOLVED("해결");

    private final String displayName;

    IncidentStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 미해결(재발 시 발생 횟수를 누적할 대상)인지. */
    public boolean isUnresolved() {
        return this != RESOLVED;
    }
}
