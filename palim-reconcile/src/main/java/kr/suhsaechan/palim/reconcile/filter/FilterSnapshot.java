package kr.suhsaechan.palim.reconcile.filter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import kr.suhsaechan.palim.reconcile.define.Pairing;

/**
 * 한 회차가 <b>무엇을 봤는지</b> 의 기록.
 *
 * <p>회차는 편집 대상이 아니라 기록이다. 남기지 않으면 지난 회차의 상세를 열 때 「오늘의 정의」
 * 로 다시 계산되고, 조건을 바꾼 순간부터 저장된 합계와 화면의 상세가 어긋난다 — 회차마다 맞기도
 * 하고 틀리기도 해서 「늘 틀린다」 보다 원인을 찾기 어렵다.
 *
 * <p><b>글로만 남긴다.</b> 조건 나무를 통째로 담지 않고 사람이 읽는 식으로 적는다. 이유가 셋이다.
 *
 * <p>하나 — 그 글이 <b>다시 계산할 수 있는 기록</b>이다. {@link ExpressionParser} 가 되읽으면
 * 그때의 조건이 그대로 되살아난다. 왕복은 시험이 지킨다.
 *
 * <p>둘 — 카탈로그에서 사라진 칸도 그대로 남는다. 구조화해 담으면 없는 칸을 가리키는 기록이 되어
 * 읽을 때 터진다.
 *
 * <p>셋 — <b>푼 날짜를 따로 담을 필요가 없다.</b> 회차는 자기가 돈 시각을 이미 안다. 「오늘+30」
 * 이 그날 무슨 날짜였는지는 그 시각으로 다시 풀면 <b>똑같은 답</b>이 나온다. 파생값을 저장하면
 * 저장한 것과 다시 푼 것이 어긋날 자리만 생긴다.
 *
 * @param leftExpression  좌측에 걸린 조건을 사람이 읽는 글로. 「전체」 이면 안 걸린 것
 * @param rightExpression 우측 조건
 * @param compareField    그때 더한 수치 칸
 * @param asOf            그 회차가 돈 시각. 상대 날짜를 다시 푸는 기준이다
 */
public record FilterSnapshot(String leftExpression, String rightExpression,
                             String compareField, Instant asOf) {

    /** 「전체」 로 읽히는 말. 조건이 없었다는 뜻이다. */
    public static final String ALL = "전체";

    public FilterSnapshot {
        leftExpression = blankToAll(leftExpression);
        rightExpression = blankToAll(rightExpression);
    }

    /** 상대 날짜 하나가 그때 무엇으로 풀렸는가. */
    public record Resolved(String raw, String value) {
    }

    /** 지금 조건에서 남길 글을 만든다. */
    public static String describe(FilterSpec spec) {
        return spec.describe();
    }

    /**
     * 옛 창고 CSV 를 <b>되읽을 수 있는 식</b>으로 적는다.
     *
     * <p>V35 이전 회차에는 이 CSV 가 유일한 기록이다. 글로만 남기면 그 회차를 다시 계산할 수
     * 없으므로, 값을 따옴표로 감싸 파서가 그대로 읽게 한다.
     */
    public static String fromLegacyCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return ALL;
        }
        String values = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .map(code -> "'" + code.replace("'", "''") + "'")
                .collect(Collectors.joining(", "));
        return values.isEmpty() ? ALL : "창고 IN (" + values + ")";
    }

    /** 좌·우 모두 조건이 없었는가. 화면이 「전부 더해서 봤다」 를 말할 자리다. */
    public boolean isAll() {
        return ALL.equals(leftExpression) && ALL.equals(rightExpression);
    }

    /**
     * 그때의 조건 그대로 <b>다시 계산할 수 있는</b> 짝을 만든다.
     *
     * <p>지난 회차의 상세는 «오늘의 정의» 가 아니라 이것으로 뜯어봐야 저장된 합계와 맞는다.
     *
     * @param leftSource 회차에는 원천 이름을 남기지 않으므로 정의에서 받는다.
     *                   원천이 바뀌면 그것은 다른 대조다
     */
    public Pairing toPairing(String leftSource, String rightSource) {
        return new Pairing(leftSource, rightSource,
                specOf(leftExpression), specOf(rightExpression), compareField);
    }

    /**
     * 상대 날짜가 <b>그때</b> 무엇으로 풀렸는가.
     *
     * <p>저장하지 않고 회차 시각으로 다시 푼다 — 같은 시각으로 같은 규칙을 풀면 같은 답이므로
     * 저장할 이유가 없고, 저장하면 어긋날 자리만 생긴다.
     */
    public List<Resolved> resolvedDates() {
        List<Resolved> out = new ArrayList<>();
        collectDates(specOf(leftExpression).root(), out);
        collectDates(specOf(rightExpression).root(), out);
        return out;
    }

    private static String blankToAll(String value) {
        return value == null || value.isBlank() ? ALL : value;
    }

    /** 읽을 수 없는 기록이 남아 있어도 화면이 죽지 않는다 — 그때는 「전체」 로 본다. */
    private static FilterSpec specOf(String expression) {
        if (expression == null || expression.isBlank() || ALL.equals(expression)) {
            return FilterSpec.all();
        }
        try {
            return new FilterSpec(ExpressionParser.parse(expression));
        } catch (RuntimeException e) {
            return FilterSpec.all();
        }
    }

    private void collectDates(FilterNode node, List<Resolved> into) {
        switch (node) {
            case FilterNode.Compare compare -> {
                if (compare.field().type() != FieldType.DATE) {
                    return;
                }
                for (String raw : compare.values()) {
                    DateToken.parse(raw)
                            .filter(DateToken::isRelative)
                            .ifPresent(token ->
                                    into.add(new Resolved(raw, token.resolve(asOf).toString())));
                }
            }
            case FilterNode.And and -> and.children().forEach(c -> collectDates(c, into));
            case FilterNode.Or or -> or.children().forEach(c -> collectDates(c, into));
            case FilterNode.Not not -> collectDates(not.child(), into);
            case FilterNode.All ignored -> {
                // 남길 것이 없다.
            }
        }
    }
}
