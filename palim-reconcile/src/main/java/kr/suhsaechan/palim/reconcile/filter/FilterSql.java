package kr.suhsaechan.palim.reconcile.filter;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQL 조각과 바인딩 값을 <b>함께</b> 쌓는 그릇.
 *
 * <p>둘을 따로 만들면 언젠가 어긋난다 — 조각에는 있는데 값이 없거나 그 반대다. 그런 어긋남은
 * 실행 시점에 「파라미터가 없습니다」 로만 드러나 어느 조건 때문인지 알기 어렵다.
 *
 * <p><b>이름은 접두어 + 순번으로만 짓는다.</b> 한 쿼리가 좌·우 두 조건을 동시에 거는 자리가
 * 있다(품목 묶기). 이름이 겹치면 뒤에 넣은 값이 앞을 덮어써 <b>양쪽이 같은 조건으로 걸린다</b> —
 * 화면은 멀쩡해 보이는데 한쪽이 통째로 비거나 엉뚱한 줄이 짝으로 잡힌다. 순번으로 뽑으면 겹칠
 * 방법이 없다.
 */
public final class FilterSql {

    private final StringBuilder sql = new StringBuilder();
    private final Map<String, Object> params = new LinkedHashMap<>();
    private final String alias;
    private final String prefix;
    private final Instant asOf;
    private int seq;

    public FilterSql(String alias, String prefix, Instant asOf) {
        this.alias = alias;
        this.prefix = prefix;
        this.asOf = asOf;
    }

    public String alias() {
        return alias;
    }

    /** 이 회차의 기준 시각. 상대 날짜를 푸는 데 쓴다. */
    public Instant asOf() {
        return asOf;
    }

    public void append(String fragment) {
        sql.append(fragment);
    }

    /** 값을 담고 그 이름을 돌려준다. 값이 SQL 문자열로 가는 길은 이것뿐이다. */
    public String bind(Object value) {
        String name = prefix + seq++;
        params.put(name, value);
        return ":" + name;
    }

    public String sql() {
        return sql.toString();
    }

    /**
     * 담긴 값들. <b>담은 순서를 지킨다.</b>
     *
     * <p>{@code Map.copyOf} 를 쓰지 않는 이유가 있다 — 그것은 순서를 흩는다. 이름이 순번으로
     * 붙으므로 순서가 흩어져도 결과는 같지만, 로그와 시험이 읽기 어려워지고 「무엇이 몇 번인지」
     * 를 눈으로 좇을 수 없게 된다.
     */
    public Map<String, Object> params() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}
