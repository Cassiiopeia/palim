package kr.suhsaechan.palim.reconcile.filter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 조건 하나를 나타내는 나무.
 *
 * <p>조건 줄과 식이 <b>둘 다 이것으로 모인다.</b> 두 입구가 각자 SQL 을 만들면 「줄로 건 것과
 * 식으로 건 것이 다르게 돈다」 가 언젠가 생기고, 그 차이는 숫자로만 드러나 원인을 찾기 어렵다.
 * 이 프로젝트는 판단이 두 군데로 갈려 어긋난 일을 이미 두 번 겪었다(07-DECISIONS 030·032).
 *
 * <p>그리고 경로가 하나면 <b>「지금 조건을 식으로 보기」 가 공짜로 나온다</b> — 나무를 글로
 * 되돌리면 된다.
 */
public sealed interface FilterNode {

    /** 아무것도 거르지 않음. */
    FilterNode ALL = new All();

    /** 자기 자신을 SQL 로 적는다. <b>여기 적힌 틀 밖의 SQL 은 만들어지지 않는다.</b> */
    void appendTo(FilterSql out);

    /** 노드 수. 폭주를 막는 쪽이 본다. */
    int nodeCount();

    /** 나무의 깊이. 마찬가지. */
    int depth();

    /** 이 나무가 아무것도 거르지 않는가. */
    default boolean isAll() {
        return this instanceof All;
    }

    record All() implements FilterNode {
        @Override
        public void appendTo(FilterSql out) {
            // 아무것도 적지 않는다. 「전부」 는 조건을 두지 않는 것이지 빈 목록이 아니다.
        }

        @Override
        public int nodeCount() {
            return 0;
        }

        @Override
        public int depth() {
            return 0;
        }
    }

    record And(List<FilterNode> children) implements FilterNode {
        public And {
            children = List.copyOf(children);
        }

        @Override
        public void appendTo(FilterSql out) {
            join(out, children, " AND ");
        }

        @Override
        public int nodeCount() {
            return 1 + children.stream().mapToInt(FilterNode::nodeCount).sum();
        }

        @Override
        public int depth() {
            return 1 + children.stream().mapToInt(FilterNode::depth).max().orElse(0);
        }
    }

    record Or(List<FilterNode> children) implements FilterNode {
        public Or {
            children = List.copyOf(children);
        }

        @Override
        public void appendTo(FilterSql out) {
            join(out, children, " OR ");
        }

        @Override
        public int nodeCount() {
            return 1 + children.stream().mapToInt(FilterNode::nodeCount).sum();
        }

        @Override
        public int depth() {
            return 1 + children.stream().mapToInt(FilterNode::depth).max().orElse(0);
        }
    }

    record Not(FilterNode child) implements FilterNode {
        @Override
        public void appendTo(FilterSql out) {
            out.append("NOT (");
            child.appendTo(out);
            out.append(")");
        }

        @Override
        public int nodeCount() {
            return 1 + child.nodeCount();
        }

        @Override
        public int depth() {
            return 1 + child.depth();
        }
    }

    /**
     * 칸 하나를 값과 견주는 잎.
     *
     * @param field    카탈로그를 거친 칸. <b>여기 임의 문자열이 올 수 없다</b>
     * @param operator 연산자
     * @param values   사람이 적은 그대로의 값들. 타입 변환은 적을 때 한다
     */
    record Compare(FilterableField field, FilterOperator operator,
                   List<String> values) implements FilterNode {

        public Compare {
            values = List.copyOf(values);
        }

        @Override
        public void appendTo(FilterSql out) {
            String column = field.sqlWith(out.alias());
            switch (operator) {
                case IN, NOT_IN -> {
                    out.append(column);
                    out.append(operator == FilterOperator.IN ? " IN (" : " NOT IN (");
                    for (int i = 0; i < values.size(); i++) {
                        if (i > 0) {
                            out.append(", ");
                        }
                        out.append(out.bind(typed(values.get(i), out)));
                    }
                    out.append(")");
                }
                case EQ -> binary(out, column, " = ");
                case NE -> binary(out, column, " <> ");
                case GT -> binary(out, column, " > ");
                case GTE -> binary(out, column, " >= ");
                case LT -> binary(out, column, " < ");
                case LTE -> binary(out, column, " <= ");
                case BETWEEN, NOT_BETWEEN -> {
                    out.append(column);
                    out.append(operator == FilterOperator.BETWEEN
                            ? " BETWEEN " : " NOT BETWEEN ");
                    out.append(out.bind(typed(values.get(0), out)));
                    out.append(" AND ");
                    out.append(out.bind(typed(values.get(1), out)));
                }
                case CONTAINS -> like(out, column, " LIKE ", "%%%s%%");
                case NOT_CONTAINS -> like(out, column, " NOT LIKE ", "%%%s%%");
                case STARTS_WITH -> like(out, column, " LIKE ", "%s%%");
                case ENDS_WITH -> like(out, column, " LIKE ", "%%%s");
                // PostgreSQL 의 대소문자 무시 정규식. 패턴 자체는 RegexGuard 가 저장 전에 본다.
                case MATCHES -> {
                    out.append(column);
                    out.append(" ~* ");
                    out.append(out.bind(values.get(0)));
                }
                // 자연키 컬럼은 NOT NULL DEFAULT '' 다. 「비었음」 은 둘을 함께 본다 —
                // 한쪽만 보면 원천에 따라 같은 뜻인데 다르게 걸린다.
                case IS_EMPTY -> out.append("coalesce(%s, '') = ''".formatted(column));
                case IS_NOT_EMPTY -> out.append("coalesce(%s, '') <> ''".formatted(column));
                case IS_TRUE -> out.append("%s IS TRUE".formatted(column));
                case IS_FALSE -> out.append("%s IS FALSE".formatted(column));
            }
        }

        private void binary(FilterSql out, String column, String op) {
            out.append(column);
            out.append(op);
            out.append(out.bind(typed(values.get(0), out)));
        }

        /**
         * {@code LIKE} 로 간다. <b>값 안의 {@code %} 와 {@code _} 는 글자로 다룬다</b> —
         * 그러지 않으면 품명에 든 「50%」 가 「무엇이든」 이 되어 엉뚱한 줄이 걸린다.
         */
        private void like(FilterSql out, String column, String op, String template) {
            String escaped = values.get(0)
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
            out.append(column);
            out.append(op);
            out.append(out.bind(template.formatted(escaped)));
            out.append(" ESCAPE '\\'");
        }

        /** 칸 타입에 맞는 값으로 바꾼다. 날짜는 이 회차의 기준 시각으로 푼다. */
        private Object typed(String raw, FilterSql out) {
            return switch (field.type()) {
                case NUMBER -> new BigDecimal(raw.trim());
                case DATE -> DateToken.parse(raw)
                        .orElseThrow(() -> new IllegalStateException(
                                "읽을 수 없는 날짜가 저장되어 있다: " + raw))
                        .resolve(out.asOf());
                case TEXT, BOOL -> raw;
            };
        }

        @Override
        public int nodeCount() {
            return 1;
        }

        @Override
        public int depth() {
            return 1;
        }
    }

    private static void join(FilterSql out, List<FilterNode> children, String glue) {
        out.append("(");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                out.append(glue);
            }
            children.get(i).appendTo(out);
        }
        out.append(")");
    }
}
