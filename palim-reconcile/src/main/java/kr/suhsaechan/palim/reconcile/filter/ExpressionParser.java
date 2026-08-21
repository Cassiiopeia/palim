package kr.suhsaechan.palim.reconcile.filter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;

/**
 * 식을 나무로 읽는다.
 *
 * <p><b>식별자 자리에 올 수 있는 것은 카탈로그 키뿐이다.</b> 문법에 임의 식별자가 없다 —
 * 그래서 표 이름·다른 칼럼·함수 이름을 적을 방법이 없다. 함수 호출·서브쿼리·세미콜론·주석도
 * 마찬가지로 문법에 없다. <b>표현할 수단이 없는 것은 막을 필요도 없다.</b>
 *
 * <p>금지어 목록으로 막지 않는 이유가 이것이다. 그 방식은 막을 것을 전부 알고 있어야 성립해
 * 언젠가 뚫린다. 여기서는 반대로 <b>통과한 것으로 무엇을 만들지</b> 를 우리가 정한다 — 통과한
 * 것은 {@link FilterNode} 가 되고, 그 노드가 만들 수 있는 SQL 은 적어 둔 틀뿐이다.
 *
 * <p>실패는 <b>저장 시점에 드러난다.</b> 못 읽는 식은 저장이 거부되고 어디서 막혔는지를 화면이
 * 가리킨다 — 도는 순간까지 미뤄지지 않는다.
 *
 * <pre>
 *   or         := and ( ("또는" | "OR") and )*
 *   and        := not ( ("그리고" | "AND") not )*
 *   not        := ("아님" | "NOT")? primary
 *   primary    := "(" or ")" | comparison
 *   comparison := field operator operand*
 *   field      := 카탈로그 키 또는 그 화면 이름
 *   operand    := '문자열' | 숫자 | 날짜토큰
 * </pre>
 */
public final class ExpressionParser {

    /** 글 길이 상한. 파싱이 되어도 비싼 것은 만들 수 있다. */
    public static final int MAX_LENGTH = 2000;
    /** 나무 깊이 상한. */
    public static final int MAX_DEPTH = 20;
    /** 노드 수 상한. */
    public static final int MAX_NODES = 200;

    private final List<String> tokens;
    private int pos;
    /** 괄호를 몇 겹 열었나. <b>AST 깊이로는 못 잡는다</b> — 괄호는 노드를 늘리지 않는다. */
    private int nesting;

    private ExpressionParser(List<String> tokens) {
        this.tokens = tokens;
    }

    /** 못 읽으면 {@link BusinessException} 을 던진다. 비어 있으면 「전부」. */
    public static FilterNode parse(String text) {
        if (text == null || text.isBlank()) {
            return FilterNode.ALL;
        }
        if (text.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.FILTER_EXPRESSION_TOO_COMPLEX,
                    "글이 %d자를 넘었습니다(상한 %d자)".formatted(text.length(), MAX_LENGTH));
        }
        ExpressionParser parser = new ExpressionParser(tokenize(text));
        FilterNode node = parser.or();
        if (parser.pos < parser.tokens.size()) {
            throw invalid("«%s» 부터는 읽을 수 없습니다".formatted(parser.tokens.get(parser.pos)));
        }
        if (node.depth() > MAX_DEPTH) {
            throw new BusinessException(ErrorCode.FILTER_EXPRESSION_TOO_COMPLEX,
                    "괄호가 %d겹을 넘었습니다(상한 %d겹)".formatted(node.depth(), MAX_DEPTH));
        }
        if (node.nodeCount() > MAX_NODES) {
            throw new BusinessException(ErrorCode.FILTER_EXPRESSION_TOO_COMPLEX,
                    "조건이 %d개를 넘었습니다(상한 %d개)".formatted(node.nodeCount(), MAX_NODES));
        }
        return node;
    }

    // ===== 문법 =====

    private FilterNode or() {
        List<FilterNode> parts = new ArrayList<>();
        parts.add(and());
        while (matchesAny("또는", "OR")) {
            pos++;
            parts.add(and());
        }
        return parts.size() == 1 ? parts.get(0) : new FilterNode.Or(parts);
    }

    private FilterNode and() {
        List<FilterNode> parts = new ArrayList<>();
        parts.add(not());
        while (matchesAny("그리고", "AND")) {
            pos++;
            parts.add(not());
        }
        return parts.size() == 1 ? parts.get(0) : new FilterNode.And(parts);
    }

    private FilterNode not() {
        if (matchesAny("아님", "NOT")) {
            pos++;
            return new FilterNode.Not(primary());
        }
        return primary();
    }

    private FilterNode primary() {
        if (matches("(")) {
            if (++nesting > MAX_DEPTH) {
                throw new BusinessException(ErrorCode.FILTER_EXPRESSION_TOO_COMPLEX,
                        "괄호가 %d겹을 넘었습니다".formatted(MAX_DEPTH));
            }
            pos++;
            FilterNode inner = or();
            expect(")");
            nesting--;
            return inner;
        }
        return comparison();
    }

    private FilterNode.Compare comparison() {
        String fieldToken = take("칸 이름이 있어야 합니다");
        FilterableField field = resolveField(fieldToken);
        String opToken = take("«%s» 뒤에 연산자가 있어야 합니다".formatted(fieldToken));
        FilterOperator operator = readOperator(opToken);

        if (!operator.supports(field.type())) {
            throw new BusinessException(ErrorCode.FILTER_OPERATOR_MISMATCH,
                    operator.label(), field.label());
        }

        List<String> values = readValues(operator, field);
        if (!operator.acceptsCount(values.size())) {
            throw new BusinessException(ErrorCode.FILTER_VALUE_COUNT,
                    operator.label(), values.size());
        }
        validateValues(field, operator, values);
        return new FilterNode.Compare(field, operator, values);
    }

    /**
     * 연산자를 읽는다.
     *
     * <p>{@code NOT IN} 처럼 두 낱말짜리가 있어 <b>다음 낱말까지 붙여 한 번 더 본다.</b>
     * 그러지 않으면 {@code NOT} 만 읽고 {@code IN} 이 값 자리로 흘러간다.
     */
    private FilterOperator readOperator(String first) {
        if (pos < tokens.size()) {
            var joined = FilterOperator.ofSymbol(first + " " + tokens.get(pos));
            if (joined.isPresent()) {
                pos++;
                return joined.get();
            }
        }
        return FilterOperator.ofSymbol(first)
                .orElseThrow(() -> invalid("«%s» 는 아는 연산자가 아닙니다".formatted(first)));
    }

    private List<String> readValues(FilterOperator operator, FilterableField field) {
        List<String> values = new ArrayList<>();
        switch (operator.arity()) {
            case NONE -> {
                // 읽을 값이 없다.
            }
            case ONE -> values.add(operand(field));
            case TWO -> {
                values.add(operand(field));
                if (!matchesAny("그리고", "AND")) {
                    throw invalid("«사이» 는 값 두 개를 «그리고» 로 이어야 합니다");
                }
                pos++;
                values.add(operand(field));
            }
            case AT_LEAST_ONE -> {
                expect("(");
                while (!matches(")")) {
                    values.add(operand(field));
                    if (matches(",")) {
                        pos++;
                    } else {
                        break;
                    }
                }
                expect(")");
            }
        }
        return values;
    }

    /**
     * 값 하나. <b>글 칸의 값은 반드시 작은따옴표로 감싼다.</b>
     *
     * <p>맨 낱말을 허용하면 무엇이 값이고 무엇이 칸 이름인지 사람도 파서도 알 수 없다 —
     * 「창고 = 01」 을 받아 주면 「창고 = 창고」 도 읽히게 된다. 숫자·날짜 칸은 따옴표 없이
     * 적는 편이 자연스러우므로 그때만 맨 낱말을 받는다.
     */
    private String operand(FilterableField field) {
        String token = take("값이 있어야 합니다");
        if (token.length() >= 2 && token.charAt(0) == '\''
                && token.charAt(token.length() - 1) == '\'') {
            return token.substring(1, token.length() - 1).replace("''", "'");
        }
        if (field.type() == FieldType.TEXT) {
            throw invalid("«%s» 는 값이 아닙니다. 글은 작은따옴표로 감싸 주세요".formatted(token));
        }
        if (isNumber(token) || DateToken.parse(token).isPresent()) {
            return token;
        }
        throw invalid("«%s» 는 값이 아닙니다".formatted(token));
    }

    private void validateValues(FilterableField field, FilterOperator operator,
                                List<String> values) {
        for (String value : values) {
            switch (field.type()) {
                case NUMBER -> {
                    if (!isNumber(value)) {
                        throw invalid("«%s» 는 숫자가 아닙니다".formatted(value));
                    }
                }
                case DATE -> DateToken.parse(value).orElseThrow(() ->
                        invalid(("«%s» 는 날짜가 아닙니다. «오늘» · «오늘+30» · «2026-08-22» "
                                + "처럼 적어 주세요").formatted(value)));
                case TEXT, BOOL -> {
                    // 글은 무엇이든 값이다. 바인딩되므로 SQL 이 되지 않는다.
                }
            }
        }
        if (operator == FilterOperator.MATCHES && !values.isEmpty()) {
            // 패턴은 PostgreSQL 이 돌리므로 폭주는 statement_timeout 이 막는다. 여기서는 문법만
            // 본다 — 자바와 PG 의 정규식 방언이 완전히 같지는 않지만, 대놓고 깨진 패턴은 잡힌다.
            try {
                Pattern.compile(values.get(0));
            } catch (PatternSyntaxException e) {
                throw invalid("정규식이 잘못됐습니다: %s".formatted(e.getDescription()));
            }
        }
    }

    private FilterableField resolveField(String token) {
        return FieldCatalog.find(token)
                .or(() -> FieldCatalog.standard().stream()
                        .filter(f -> f.label().equals(token))
                        .findFirst())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.FILTER_FIELD_UNKNOWN, token));
    }

    // ===== 토큰 =====

    /**
     * 글을 낱말로 자른다.
     *
     * <p>{@code '…'} 안은 통째로 한 낱말이다. {@code ''} 는 따옴표 한 글자를 뜻한다 —
     * SQL 과 같은 규칙이라 사람이 새로 배울 것이 없다.
     */
    private static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(' || c == ')' || c == ',') {
                out.add(String.valueOf(c));
                i++;
            } else if (c == '\'') {
                int j = i + 1;
                StringBuilder sb = new StringBuilder("'");
                boolean closed = false;
                while (j < text.length()) {
                    if (text.charAt(j) == '\'') {
                        if (j + 1 < text.length() && text.charAt(j + 1) == '\'') {
                            sb.append("''");
                            j += 2;
                            continue;
                        }
                        closed = true;
                        break;
                    }
                    sb.append(text.charAt(j));
                    j++;
                }
                if (!closed) {
                    throw invalid("따옴표가 닫히지 않았습니다");
                }
                out.add(sb.append('\'').toString());
                i = j + 1;
            } else if (isSymbolChar(c)) {
                int j = i;
                while (j < text.length() && isSymbolChar(text.charAt(j))) {
                    j++;
                }
                out.add(text.substring(i, j));
                i = j;
            } else {
                int j = i;
                while (j < text.length() && !Character.isWhitespace(text.charAt(j))
                        && "(),'".indexOf(text.charAt(j)) < 0
                        && !isSymbolChar(text.charAt(j))) {
                    j++;
                }
                out.add(text.substring(i, j));
                i = j;
            }
        }
        return out;
    }

    private static boolean isSymbolChar(char c) {
        return "=<>!≠≥≤".indexOf(c) >= 0;
    }

    private boolean matches(String token) {
        return pos < tokens.size() && tokens.get(pos).equals(token);
    }

    private boolean matchesAny(String... candidates) {
        if (pos >= tokens.size()) {
            return false;
        }
        String token = tokens.get(pos);
        for (String candidate : candidates) {
            if (token.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void expect(String token) {
        if (!matches(token)) {
            throw invalid("«%s» 가 있어야 합니다".formatted(token));
        }
        pos++;
    }

    private String take(String message) {
        if (pos >= tokens.size()) {
            throw invalid(message);
        }
        return tokens.get(pos++);
    }

    private static BusinessException invalid(String detail) {
        return new BusinessException(ErrorCode.FILTER_EXPRESSION_INVALID, detail);
    }

    private static boolean isNumber(String token) {
        try {
            new BigDecimal(token);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
