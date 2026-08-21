package kr.suhsaechan.palim.reconcile.filter;

import java.time.Instant;
import java.util.Map;

/**
 * 한 원천에서 <b>무엇을 볼지</b>.
 *
 * <p>{@code WarehouseScope} 를 대신한다. 창고 하나만 고를 수 있던 것을 어느 칸으로든 걸 수
 * 있게 넓힌 것이고, 창고는 이 안의 조건 한 줄이 되었다.
 *
 * <p><b>왜 값 객체로 두는가.</b> 조건이 걸려야 하는 쿼리가 한 곳이 아니다 — 합계·미매칭·
 * 뜯어보기·품목 묶기. 쿼리마다 따로 적으면 한 곳이 빠지고, <b>빠진 곳만 다른 숫자를 낸다.</b>
 * 그러면 「합계는 이런데 뜯어보면 다르다」 가 되어 어느 쪽을 믿어야 할지 알 수 없다.
 */
public record FilterSpec(FilterNode root) {

    /** 기본 바인딩 접두어. 좌·우를 한 쿼리에 걸 때는 서로 다른 값을 준다. */
    public static final String PREFIX = "f";

    private static final FilterSpec ALL = new FilterSpec(FilterNode.ALL);

    public FilterSpec {
        root = root == null ? FilterNode.ALL : root;
    }

    /** 아무것도 거르지 않는 조건. */
    public static FilterSpec all() {
        return ALL;
    }

    public boolean isAll() {
        return root.isAll();
    }

    /** 조각과 값을 한 번에. 두 번 불러도 같은 결과다. */
    public Compiled compile(String alias, String prefix, Instant asOf) {
        if (isAll()) {
            return new Compiled("", Map.of());
        }
        FilterSql out = new FilterSql(alias, prefix, asOf);
        out.append(" AND ");
        // 조각은 «어디에 붙여도 안전해야» 한다. 부르는 쪽이 OR 이 섞인 WHERE 절에 이어 붙이면
        // 괄호 없는 조각은 우선순위가 뒤집혀 조용히 다른 뜻이 된다. And·Or 는 스스로 감싸므로
        // 그때만 두 겹이 되는 것을 피한다.
        boolean selfWrapping = root instanceof FilterNode.And || root instanceof FilterNode.Or;
        if (!selfWrapping) {
            out.append("(");
        }
        root.appendTo(out);
        if (!selfWrapping) {
            out.append(")");
        }
        return new Compiled(out.sql(), out.params());
    }

    /**
     * 쿼리에 끼울 조건 조각.
     *
     * <p>비어 있으면 <b>빈 문자열</b>을 준다. {@code IN ()} 은 SQL 문법 오류라 「전부」 를
     * 빈 목록으로 표현할 수 없기 때문이다.
     */
    public String sqlAnd(String alias, String prefix, Instant asOf) {
        return compile(alias, prefix, asOf).sql();
    }

    public String sqlAnd(String alias, Instant asOf) {
        return sqlAnd(alias, PREFIX, asOf);
    }

    /** 바인딩할 값. 조각에 없는 파라미터를 넘기면 바인딩에서 거부당하므로 비면 빈 맵이다. */
    public Map<String, Object> params(String prefix, Instant asOf) {
        return compile("s", prefix, asOf).params();
    }

    public Map<String, Object> params(Instant asOf) {
        return params(PREFIX, asOf);
    }

    /** 화면에 보여줄 말. 「전체」 인지 무엇이 걸렸는지가 한눈에 보여야 잘못 걸린 것을 알아챈다. */
    public String describe() {
        // Task 7 에서 ExpressionWriter.write(root) 로 바꾼다. 그전까지는 컴파일이 되게만 둔다.
        return isAll() ? "전체" : "조건 " + root.nodeCount() + "개";
    }

    /** 컴파일 결과. 조각과 값은 언제나 함께 다닌다. */
    public record Compiled(String sql, Map<String, Object> params) {
    }
}
