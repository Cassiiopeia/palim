package kr.suhsaechan.palim.reconcile.filter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 나무를 사람이 읽는 글로 되돌린다.
 *
 * <p>조건 줄과 식이 한 나무로 모이므로 이것이 <b>공짜로</b> 나온다. 「지금 조건을 식으로 보기」
 * 가 그 결과이고, 사람이 조건 줄로 시작해 식을 배우는 길이 된다.
 *
 * <p><b>되읽을 수 있는 글을 쓴다.</b> {@link ExpressionParser} 가 다시 읽어 같은 나무가 되어야
 * 한다 — 그러지 않으면 「식으로 보기」 를 눌러 나온 글을 저장할 수 없다.
 */
public final class ExpressionWriter {

    private ExpressionWriter() {
    }

    public static String write(FilterNode node) {
        return switch (node) {
            case FilterNode.All ignored -> "전체";
            case FilterNode.And and -> join(and.children(), " 그리고 ");
            case FilterNode.Or or -> join(or.children(), " 또는 ");
            case FilterNode.Not not -> "아님 (" + write(not.child()) + ")";
            case FilterNode.Compare compare -> writeCompare(compare);
        };
    }

    private static String join(List<FilterNode> children, String glue) {
        return children.stream()
                .map(child -> child instanceof FilterNode.Compare
                        ? write(child) : "(" + write(child) + ")")
                .collect(Collectors.joining(glue));
    }

    private static String writeCompare(FilterNode.Compare compare) {
        String head = "%s %s".formatted(compare.field().label(), compare.operator().symbol());
        return switch (compare.operator().arity()) {
            case NONE -> head;
            case ONE -> head + " " + literal(compare, 0);
            case TWO -> head + " " + literal(compare, 0) + " 그리고 " + literal(compare, 1);
            case AT_LEAST_ONE -> head + " (" + compare.values().stream()
                    .map(value -> quote(compare, value))
                    .collect(Collectors.joining(", ")) + ")";
        };
    }

    private static String literal(FilterNode.Compare compare, int index) {
        return quote(compare, compare.values().get(index));
    }

    /**
     * 값을 글로 적는다.
     *
     * <p>날짜·숫자는 따옴표 없이 적는다 — {@code 오늘+30} 을 {@code '오늘+30'} 으로 적으면
     * 되읽을 때 글자로 취급되어 상대 날짜가 죽는다.
     */
    private static String quote(FilterNode.Compare compare, String value) {
        if (compare.field().type() == FieldType.NUMBER
                || compare.field().type() == FieldType.DATE) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }
}
