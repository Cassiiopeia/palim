package kr.suhsaechan.palim.audit;

/**
 * 감사 로그 검색 대상 필드.
 *
 * <p>화면의 "조회 조건" 드롭다운에 대응한다. 검색어를 여러 컬럼에 OR 로 흘리지 않고 대상을
 * 고르게 하는 이유는, IP 로 검색할 때 상품명에 IP 같은 문자열이 걸리는 오탐을 없애기 위함이다.
 */
public enum AuditSearchField {

    ACTOR_ID("아이디"),
    ACTOR_NAME("이름"),
    CLIENT_IP("IP"),
    SUMMARY("내용");

    private final String displayName;

    AuditSearchField(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
