package kr.suhsaechan.palim.reconcile.filter;

import java.time.Instant;
import kr.suhsaechan.palim.reconcile.define.Pairing;
import java.util.ArrayList;
import java.util.List;

/**
 * 한 회차가 <b>무엇을 봤는지</b> 의 기록.
 *
 * <p>회차는 편집 대상이 아니라 기록이다. 남기지 않으면 지난 회차의 상세를 열 때 「오늘의 정의」
 * 로 다시 계산되고, 조건을 바꾼 순간부터 저장된 합계와 화면의 상세가 어긋난다 — 회차마다 맞기도
 * 하고 틀리기도 해서 「늘 틀린다」 보다 원인을 찾기 어렵다.
 *
 * <p>푼 값과 원래 표현을 <b>함께</b> 적는다. 「그때 무슨 날짜로 걸렸나」 와 「무슨 규칙이었나」 는
 * 다른 질문이고 둘 다 필요하다 — 앞의 것 없이는 결과를 재현할 수 없고, 뒤의 것 없이는 왜 그
 * 날짜였는지 알 수 없다.
 *
 * @param leftExpression  좌측에 걸린 조건을 사람이 읽는 글로. 「전체」 이면 안 걸린 것
 * @param rightExpression 우측 조건
 * @param compareField    그때 더한 수치 칸
 * @param resolvedDates   푼 상대 날짜들
 */
public record FilterSnapshot(String leftExpression, String rightExpression,
                             String compareField, List<Resolved> resolvedDates) {

    /** 「전체」 로 읽히는 말. 조건이 없었다는 뜻이다. */
    public static final String ALL = "전체";

    public FilterSnapshot {
        leftExpression = leftExpression == null ? ALL : leftExpression;
        rightExpression = rightExpression == null ? ALL : rightExpression;
        resolvedDates = resolvedDates == null ? List.of() : List.copyOf(resolvedDates);
    }

    /** 상대 날짜 하나가 그때 무엇으로 풀렸는가. */
    public record Resolved(String raw, String value) {
    }

    /** 지금 조건에서 기록을 만든다. */
    public static FilterSnapshot of(FilterSpec left, FilterSpec right,
                                    String compareField, Instant asOf) {
        List<Resolved> resolved = new ArrayList<>();
        collectDates(left.root(), asOf, resolved);
        collectDates(right.root(), asOf, resolved);
        return new FilterSnapshot(left.describe(), right.describe(), compareField, resolved);
    }

    /** V35 이전 회차. 옛 창고 CSV 를 읽는다 — 그것이 그 회차의 유일한 기록이다. */
    public static FilterSnapshot fromLegacy(String leftWarehouses, String rightWarehouses,
                                            String compareField) {
        return new FilterSnapshot(describeLegacy(leftWarehouses),
                describeLegacy(rightWarehouses), compareField, List.of());
    }

    /** 좌·우 모두 조건이 없었는가. 화면이 「전부 더해서 봤다」 를 말할 자리다. */
    public boolean isAll() {
        return ALL.equals(leftExpression) && ALL.equals(rightExpression);
    }

    /**
     * 그때의 조건 그대로 <b>다시 계산할 수 있는</b> 짝을 만든다.
     *
     * <p>지난 회차의 상세는 «오늘의 정의» 가 아니라 이것으로 뜯어봐야 저장된 합계와 맞는다.
     * 기록한 글을 그대로 되읽는 방식이라, {@code ExpressionWriter} → {@code ExpressionParser}
     * 왕복이 성립하는 한 여기도 성립한다 — 그 왕복은 시험이 지킨다.
     *
     * <p>상대 날짜는 <b>그때 푼 값</b>이 아니라 표현 그대로 되읽는다. 지난 회차를 오늘 열면
     * 하루가 밀리므로, 정확한 값이 필요한 자리는 {@link #resolvedDates()} 를 본다.
     *
     * @param leftSource 회차에는 원천 이름을 남기지 않으므로 정의에서 받는다.
     *                   원천이 바뀌면 그것은 다른 대조다
     */
    public Pairing toPairing(String leftSource, String rightSource) {
        return new Pairing(leftSource, rightSource,
                specOf(leftExpression), specOf(rightExpression), compareField);
    }

    private static FilterSpec specOf(String expression) {
        if (expression == null || expression.isBlank() || ALL.equals(expression)) {
            return FilterSpec.all();
        }
        return new FilterSpec(ExpressionParser.parse(expression));
    }

    /**
     * 옛 창고 CSV 를 <b>되읽을 수 있는 식</b>으로 적는다.
     *
     * <p>글로만 남기면 그 회차를 다시 계산할 수 없다. 값을 따옴표로 감싸 두면 파서가 그대로
     * 읽어 그때의 조건이 되살아난다.
     */
    private static String describeLegacy(String csv) {
        if (csv == null || csv.isBlank()) {
            return ALL;
        }
        String values = java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .map(code -> "'" + code.replace("'", "''") + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        return values.isEmpty() ? ALL : "창고 IN (" + values + ")";
    }

    private static void collectDates(FilterNode node, Instant asOf, List<Resolved> into) {
        switch (node) {
            case FilterNode.Compare compare -> {
                if (compare.field().type() != FieldType.DATE) {
                    return;
                }
                for (String raw : compare.values()) {
                    DateToken.parse(raw).ifPresent(token ->
                            into.add(new Resolved(raw, token.resolve(asOf).toString())));
                }
            }
            case FilterNode.And and -> and.children().forEach(c -> collectDates(c, asOf, into));
            case FilterNode.Or or -> or.children().forEach(c -> collectDates(c, asOf, into));
            case FilterNode.Not not -> collectDates(not.child(), asOf, into);
            case FilterNode.All ignored -> {
                // 남길 것이 없다.
            }
        }
    }
}
