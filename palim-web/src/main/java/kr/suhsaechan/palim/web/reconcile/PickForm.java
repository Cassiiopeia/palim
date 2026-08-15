package kr.suhsaechan.palim.web.reconcile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import kr.suhsaechan.palim.reconcile.match.SourceItemBrowser;
import lombok.Getter;
import lombok.Setter;

/**
 * 손으로 잇는 화면의 상태 — <b>담아 둔 것과 목록을 좁힌 조건.</b>
 *
 * <p>왜 폼 하나에 다 담는가. 이 화면은 검색하고 쪽을 넘기면서 양쪽에서 하나씩 담아 가는
 * 흐름인데, <b>화면 안에 코드를 넣을 수 없다</b>(CSP 가 인라인 스크립트를 조용히 막는다 —
 * 07-DECISIONS 031). 그래서 「담아 둔 것」 을 브라우저 쪽에 들고 있을 방법이 없다.
 *
 * <p>주소(쿼리 문자열)에 담는 길을 먼저 생각했지만 <b>안 된다.</b> HTML 의 GET 폼은 제출할 때
 * 기존 쿼리 문자열을 통째로 버리고 자기 입력칸만 보낸다. 검색 한 번이면 담아 둔 것이 조용히
 * 사라진다 — 이 화면에서 가장 흔한 동작이 바로 그것이다.
 *
 * <p>그래서 <b>좌·우 목록과 담은 것을 모두 한 POST 폼 안에 둔다.</b> 검색도 쪽 넘김도 담기도
 * 전부 같은 폼의 제출 버튼이라, 무엇을 눌러도 나머지가 함께 실려 간다. 자바스크립트 0줄이고
 * 규약도 없다.
 */
@Getter
@Setter
public class PickForm {

    /** 한 번에 담을 수 있는 최대. 미리보기가 사람 눈에 들어오는 선이다. */
    public static final int PICK_LIMIT = 20;

    /**
     * 담은 것 — {@code 원천|품목코드}. 폼이 숨은 입력으로 실어 나른다.
     *
     * <p>계수를 여기 붙이지 않는다. 품목코드에 {@code |} 가 들어 있을 수 있어 자르는 규약이
     * 필요해지는데, 나란한 목록 하나를 더 두면 그 규약 자체가 없어진다.
     */
    private List<String> pick = new ArrayList<>();

    /**
     * 담은 것의 계수 — {@link #pick} 과 <b>같은 순서</b>.
     *
     * <p>브라우저는 문서에 놓인 순서대로 보내고 한 줄에 하나씩만 그리므로 짝이 어긋나지 않는다.
     * 그래도 개수가 다르면 없는 쪽을 1로 본다 — 화면이 깨지느니 계수를 1로 보는 편이 낫다.
     */
    private List<String> pickFactor = new ArrayList<>();

    /** 왼쪽 검색어. */
    private String lq = "";

    /** 왼쪽 쪽 번호(0부터). */
    private int lp = 0;

    /** 오른쪽 검색어. */
    private String rq = "";

    /** 오른쪽 쪽 번호. */
    private int rp = 0;

    /** 어떤 상태의 품목을 볼지. 비면 «아직 안 이음». */
    private String state = "";

    /** 새로 만들 때 쓸 이름. 기존 물건에 붙일 때는 쓰이지 않는다. */
    private String newName = "";

    public SourceItemBrowser.LinkState linkState() {
        try {
            return state == null || state.isBlank()
                    ? SourceItemBrowser.LinkState.UNLINKED
                    : SourceItemBrowser.LinkState.valueOf(state);
        } catch (IllegalArgumentException e) {
            // 주소를 손으로 고쳐 이상한 값이 와도 화면이 깨지지 않는다.
            return SourceItemBrowser.LinkState.UNLINKED;
        }
    }

    /**
     * 담은 것들.
     *
     * <p>형식이 깨진 줄은 <b>조용히 버리지 않고</b> 계수 1로 본다 — 담았다고 생각한 것이
     * 사라지는 편이 잘못된 계수보다 나쁘다. 상한을 넘으면 앞에서부터 자른다.
     */
    public List<Picked> picked() {
        List<Picked> parsed = new ArrayList<>();
        for (int i = 0; i < pick.size() && parsed.size() < PICK_LIMIT; i++) {
            String raw = pick.get(i);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            // 원천 이름에는 | 가 없다(연동 코드 규칙). 품목코드에는 있을 수 있으므로 «첫»
            // 구분자에서만 가른다 — 뒤는 통째로 품목코드다.
            int cut = raw.indexOf('|');
            if (cut <= 0 || cut == raw.length() - 1) {
                continue;
            }
            parsed.add(new Picked(raw.substring(0, cut), raw.substring(cut + 1),
                    factorAt(i)));
        }
        return parsed;
    }

    /** 못 읽으면 1. 담은 것이 사라지는 편이 잘못된 계수보다 나쁘다. */
    private BigDecimal factorAt(int index) {
        if (index >= pickFactor.size()) {
            return BigDecimal.ONE;
        }
        String raw = pickFactor.get(index);
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ONE;
        }
        try {
            BigDecimal parsed = new BigDecimal(raw.trim());
            return parsed.signum() <= 0 ? BigDecimal.ONE : parsed;
        } catch (NumberFormatException e) {
            return BigDecimal.ONE;
        }
    }

    /** 담기 — 이미 있으면 그대로 둔다. 상한을 넘으면 담기지 않는다. */
    public boolean add(String token) {
        if (token == null || token.isBlank() || pick.contains(token)
                || pick.size() >= PICK_LIMIT) {
            return false;
        }
        pick.add(token);
        pickFactor.add("1");
        return true;
    }

    /** 빼기 — 계수도 같은 자리에서 함께 뺀다. */
    public void drop(String token) {
        int at = pick.indexOf(token);
        if (at < 0) {
            return;
        }
        pick.remove(at);
        if (at < pickFactor.size()) {
            pickFactor.remove(at);
        }
    }

    /** 담은 것 하나. */
    public record Picked(String source, String itemRef, BigDecimal factor) {

        /** 폼이 실어 나르는 형태. */
        public String token() {
            return source + "|" + itemRef;
        }
    }
}
