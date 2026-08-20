package kr.suhsaechan.palim.reconcile.define;

import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * 한 원천에서 <b>어느 창고를 볼지</b>.
 *
 * <p>재고를 맡긴 곳은 자기가 보관 중인 것만 안다. 그런데 전산 쪽에는 창고가 여럿이다 —
 * 위탁 창고, 사무실, 매장. <b>전부 더해서 견주면 위탁하지 않은 물량만큼 무조건 어긋난다.</b>
 * 실측으로는 그 차이가 총합 1개짜리 대조를 754개짜리로 만들었고, 맞던 품목까지 틀린 것으로
 * 보이게 했다(일치 11건 → 3건).
 *
 * <p><b>비어 있으면 전부 본다.</b> 지금까지의 동작이 그것이고, 이미 만들어 둔 정의가 이 값이
 * 없는 채로 있기 때문이다. 대신 양쪽 창고 수가 어긋나면 화면이 짝을 정하라고 안내한다 —
 * 조용히 틀린 답을 내는 것보다 낫다.
 *
 * <p><b>왜 값 객체로 두는가.</b> 창고 조건이 걸려야 하는 쿼리가 한 곳이 아니다 — 합계·미매칭·
 * 뜯어보기·품목 묶기. 쿼리마다 따로 적으면 한 곳이 빠지고, <b>빠진 곳만 다른 숫자를 낸다.</b>
 * 그러면 「합계는 이런데 뜯어보면 다르다」 가 되어 어느 쪽을 믿어야 할지 알 수 없다.
 */
public record WarehouseScope(List<String> codes) {

    /** 쿼리에 바인딩할 이름. 조각과 파라미터가 같은 이름을 쓰도록 여기 한 번만 적는다. */
    public static final String PARAM = "warehouseCodes";

    private static final WarehouseScope ALL = new WarehouseScope(List.of());

    public WarehouseScope {
        codes = codes == null ? List.of() : List.copyOf(codes);
    }

    /** 전부 보는 범위. */
    public static WarehouseScope all() {
        return ALL;
    }

    /**
     * 저장된 문자열을 읽는다.
     *
     * <p>쉼표로 구분한다. 빈 칸과 중복은 버린다 — 화면에서 온 값이라 둘 다 들어올 수 있고,
     * 중복이 있으면 {@code IN} 절만 길어진다.
     */
    public static WarehouseScope parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return ALL;
        }
        List<String> parsed = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
        return parsed.isEmpty() ? ALL : new WarehouseScope(parsed);
    }

    /** 저장할 문자열. 비면 {@code null} — 빈 문자열로 두면 「고른 것이 없음」 과 구분되지 않는다. */
    public String toStored() {
        return codes.isEmpty() ? null : String.join(",", codes);
    }

    public boolean isAll() {
        return codes.isEmpty();
    }

    /**
     * 쿼리에 끼울 조건 조각.
     *
     * <p>비어 있으면 <b>빈 문자열</b>을 준다. {@code IN ()} 은 SQL 문법 오류라 「전부」 를 빈
     * 목록으로 표현할 수 없기 때문이다.
     *
     * @param alias {@code std_stock_snapshot} 의 별칭
     */
    public String sqlAnd(String alias) {
        return sqlAnd(alias, PARAM);
    }

    /**
     * 이름을 지정해 조각을 만든다.
     *
     * <p>한 쿼리가 <b>좌·우 두 범위를 동시에</b> 거는 자리가 있다(품목 잇기 화면은 양쪽 원천을
     * 한 번에 읽는다). 그때 이름이 같으면 뒤에 넣은 값이 앞을 덮어써 <b>양쪽이 같은 창고로
     * 걸린다</b> — 화면은 멀쩡해 보이는데 한쪽이 통째로 비거나 엉뚱한 줄이 짝으로 잡힌다.
     */
    public String sqlAnd(String alias, String paramName) {
        return isAll() ? "" : " AND %s.warehouse_code IN (:%s)".formatted(alias, paramName);
    }

    /**
     * 바인딩할 값.
     *
     * <p>비어 있으면 빈 맵이다 — 조각에 없는 파라미터를 넘기면 바인딩에서 거부당한다.
     */
    public Map<String, Object> params() {
        return params(PARAM);
    }

    public Map<String, Object> params(String paramName) {
        return isAll() ? Map.of() : Map.of(paramName, codes);
    }

    /** 화면에 보여줄 말. 「전체」 인지 몇 곳인지가 한눈에 보여야 짝이 어긋난 것을 알아챈다. */
    public String describe() {
        return isAll() ? "전체" : String.join(", ", codes);
    }
}
