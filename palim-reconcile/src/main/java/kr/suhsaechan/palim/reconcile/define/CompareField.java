package kr.suhsaechan.palim.reconcile.define;

import java.util.Set;

/**
 * <b>무엇을 견줄지</b> — 스냅샷의 어느 수치 칸을 더할 것인가.
 *
 * <p>칸 이름이 SQL 에 <b>그대로 들어가는 자리</b>라 허용 목록으로 거른다. 정의 화면이 막더라도
 * 여기서 한 번 더 막는다 — 뚫리면 조회 범위가 통째로 열린다.
 *
 * <p><b>왜 한 곳에 모으는가.</b> 이 목록이 집계 클래스 안에 갇혀 있는 동안, 뜯어보기는 그것을
 * 쓸 수 없어 {@code base_quantity} 를 <b>고정으로</b> 박아 두고 있었다. 그래서 정의가 다른 칸을
 * 고른 곳에서는 합계와 뜯어보기가 서로 다른 수를 냈다 — 같은 화면에서 위아래로 붙어 나오는데
 * 어느 쪽을 믿을지 알 방법이 없었다.
 *
 * <p>허용 목록이 두 벌이 되면 한쪽만 늘어나 같은 병이 다시 난다. 그래서 목록도, 되돌리는 규칙도
 * 여기 하나만 둔다.
 */
public final class CompareField {

    /** 기본값. 단위를 환산해 둔 수량이라 원천마다 단위가 달라도 견줄 수 있다. */
    public static final String DEFAULT = "base_quantity";

    /**
     * 견줄 수 있는 수치 칸.
     *
     * <p>늘릴 때는 <b>스냅샷 표에 실재하는 수치 칸인지</b> 확인한다. 여기 이름이 그대로 SQL 이
     * 된다.
     */
    private static final Set<String> ALLOWED = Set.of(
            "base_quantity", "quantity", "available_quantity", "reserved_quantity", "amount");

    private CompareField() {
    }

    /**
     * 쓸 수 있는 칸 이름으로 되돌린다.
     *
     * <p>허용 목록에 없으면 <b>기본값으로 되돌린다.</b> 예외를 던지지 않는 이유는, 정의가 옛
     * 칸 이름을 들고 있을 때 화면 전체가 열리지 않으면 그 값을 고칠 자리조차 사라지기 때문이다.
     */
    public static String sanitize(String field) {
        // Set.of() 는 «불변» 이라 contains(null) 에서 NullPointerException 을 던진다.
        // 견줄 칸을 정하지 않은 자리(Pairing.ofSources 등)가 null 을 넘기므로 먼저 막는다.
        return field != null && ALLOWED.contains(field) ? field : DEFAULT;
    }

    /** 화면이 고를 수 있는 칸. 정렬해 돌려준다 — 목록 순서가 요청마다 바뀌면 눈에 거슬린다. */
    public static Set<String> allowed() {
        return ALLOWED;
    }
}
