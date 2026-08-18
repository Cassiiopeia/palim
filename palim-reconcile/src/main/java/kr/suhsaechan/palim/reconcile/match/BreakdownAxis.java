package kr.suhsaechan.palim.reconcile.match;

/**
 * 합계를 <b>무엇을 기준으로</b> 뜯어볼 것인가.
 *
 * <h2>왜 자료로 두는가</h2>
 *
 * <p>처음에는 「품명이 닮은 것끼리」 하나로 못박아 두었다. 어느 발주사의 자료에서는 로트가
 * <b>품명 안에</b> 들어 있어서 그것만으로 잘 맞았기 때문이다. 그런데 그건 <b>그 자료의 사정</b>
 * 이지 이 프로그램의 성질이 아니다.
 *
 * <p>로트를 별도 칸으로 주는 시스템, 창고별로 갈라 봐야 하는 회사, 품목코드를 양쪽이 똑같이
 * 쓰는 곳 — 전부 <b>다른 기준</b>이 필요하다. 기준이 코드에 박혀 있으면 그런 곳에서는 이 화면이
 * 통째로 쓸모없어지고, 고칠 방법도 없다.
 *
 * <p>그래서 기준을 <b>표준 모델의 칸 목록에서 고른다.</b> 칸이 늘면 고를 것도 저절로 는다 —
 * 새 기준을 넣으려고 이 파일을 고칠 일이 없다.
 *
 * <h2>고른 기준이 그 자료에 안 맞을 때</h2>
 *
 * <p><b>조용히 이상하게 굴지 않는다.</b> 한쪽에 그 칸 값이 아예 없으면 짝을 지을 수 없는데,
 * 그때 아무 말 없이 「전부 짝 없음」 을 보여주면 사람은 자료가 잘못된 줄 안다. 화면이
 * 「이쪽에는 그 값이 없습니다」 라고 말해야 다른 기준을 골라 볼 생각을 한다.
 *
 * @param fieldKey {@link Kind#FIELD} 일 때 견줄 칸. 표준 모델에 실제로 있는 칸이어야 한다
 * @param label    사람에게 보여줄 이름
 */
public record BreakdownAxis(Kind kind, String fieldKey, String label) {

    /** 어떤 방식으로 짝을 짓나. */
    public enum Kind {
        /** 품명이 <b>닮은</b> 것끼리. 값이 정확히 같지 않아도 되므로 어디서나 일단 돈다. */
        NAME,
        /** 고른 칸의 값이 <b>정확히 같은</b> 것끼리. */
        FIELD,
        /** 짝짓지 않고 양쪽을 그대로 늘어놓는다. 견줄 근거가 없을 때 억지로 맺지 않는다. */
        NONE
    }

    /** 어디서나 일단 도는 기본값. 자료를 모르는 상태에서 고를 수 있는 유일한 기준이다. */
    public static BreakdownAxis byName() {
        return new BreakdownAxis(Kind.NAME, "", "품명이 닮은 것끼리");
    }

    public static BreakdownAxis none() {
        return new BreakdownAxis(Kind.NONE, "", "짝짓지 않고 그대로");
    }

    public static BreakdownAxis field(String fieldKey, String displayName) {
        return new BreakdownAxis(Kind.FIELD, fieldKey, displayName + " 가 같은 것끼리");
    }

    /** 화면·주소가 주고받는 값. */
    public String token() {
        return switch (kind) {
            case NAME -> "NAME";
            case NONE -> "NONE";
            case FIELD -> "FIELD:" + fieldKey;
        };
    }

    public boolean pairs() {
        return kind != Kind.NONE;
    }
}
