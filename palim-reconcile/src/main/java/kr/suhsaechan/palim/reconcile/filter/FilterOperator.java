package kr.suhsaechan.palim.reconcile.filter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 조건 한 줄의 <b>문법</b>.
 *
 * <p>칸 이름은 담긴 자료에서 뽑는데 연산자는 코드에 박는다. 비대칭이지만 이유가 있다 —
 * 연산자 하나마다 SQL 틀·값 개수 규칙·화면 위젯·이름 넷이 붙고, 그것은 전부 코드다. 표로 빼도
 * 셋은 여전히 코드라 「확장 가능한 척」 만 된다.
 *
 * <p>그래서 확장 지점을 칸에 둔다. 칸은 자료에서 나오니 무한하고, 연산자는 그 칸을 다루는
 * 문법이라 유한하다. <b>유한하다면 타입별로 빠짐없어야</b> 「이 연산자가 없어서 못 한다」 가
 * 나오지 않는다.
 *
 * <p><b>부정형을 짝으로 둔다.</b> {@code IN} 만 있고 {@code NOT_IN} 이 없으면 「불량만 빼고
 * 전부」 를 하려고 나머지를 전부 체크해야 하는데, 값이 늘어나는 날 새 값이 빠진 채로 돈다.
 * 부정형은 편의가 아니라 <b>자료가 늘어도 안 낡는 조건</b>을 쓸 수 있게 하는 것이다.
 */
public enum FilterOperator {

    IN("이것만", "IN", Arity.AT_LEAST_ONE, EnumSet.of(FieldType.TEXT, FieldType.NUMBER)),
    NOT_IN("이것 빼고", "NOT IN", Arity.AT_LEAST_ONE,
            EnumSet.of(FieldType.TEXT, FieldType.NUMBER)),

    EQ("같음", "=", Arity.ONE,
            EnumSet.of(FieldType.TEXT, FieldType.NUMBER, FieldType.DATE)),
    NE("다름", "≠", Arity.ONE,
            EnumSet.of(FieldType.TEXT, FieldType.NUMBER, FieldType.DATE)),

    GT("초과", ">", Arity.ONE, EnumSet.of(FieldType.NUMBER, FieldType.DATE)),
    GTE("이후", ">=", Arity.ONE, EnumSet.of(FieldType.NUMBER, FieldType.DATE)),
    LT("미만", "<", Arity.ONE, EnumSet.of(FieldType.NUMBER, FieldType.DATE)),
    LTE("이전", "<=", Arity.ONE, EnumSet.of(FieldType.NUMBER, FieldType.DATE)),

    BETWEEN("사이", "BETWEEN", Arity.TWO, EnumSet.of(FieldType.NUMBER, FieldType.DATE)),
    NOT_BETWEEN("사이 빼고", "NOT BETWEEN", Arity.TWO,
            EnumSet.of(FieldType.NUMBER, FieldType.DATE)),

    CONTAINS("포함", "포함", Arity.ONE, EnumSet.of(FieldType.TEXT)),
    NOT_CONTAINS("포함 안 함", "포함안함", Arity.ONE, EnumSet.of(FieldType.TEXT)),
    STARTS_WITH("이렇게 시작", "시작", Arity.ONE, EnumSet.of(FieldType.TEXT)),
    ENDS_WITH("이렇게 끝", "끝", Arity.ONE, EnumSet.of(FieldType.TEXT)),
    /** 정규식. {@code RegexGuard} 로 폭주하는 패턴을 막는다 — 정규화 규칙에서 쓰던 장치다. */
    MATCHES("규칙에 맞음", "MATCHES", Arity.ONE, EnumSet.of(FieldType.TEXT)),

    IS_EMPTY("비었음", "비었음", Arity.NONE,
            EnumSet.of(FieldType.TEXT, FieldType.NUMBER, FieldType.DATE, FieldType.BOOL)),
    IS_NOT_EMPTY("값 있음", "값있음", Arity.NONE,
            EnumSet.of(FieldType.TEXT, FieldType.NUMBER, FieldType.DATE, FieldType.BOOL)),

    IS_TRUE("참", "참", Arity.NONE, EnumSet.of(FieldType.BOOL)),
    IS_FALSE("거짓", "거짓", Arity.NONE, EnumSet.of(FieldType.BOOL));

    /** 값이 몇 개 필요한가. 화면과 서버가 <b>같은 규칙을 한 곳에서</b> 읽는다. */
    public enum Arity {
        NONE, ONE, TWO, AT_LEAST_ONE
    }

    private final String label;
    private final String symbol;
    private final Arity arity;
    private final Set<FieldType> types;

    FilterOperator(String label, String symbol, Arity arity, Set<FieldType> types) {
        this.label = label;
        this.symbol = symbol;
        this.arity = arity;
        this.types = types;
    }

    public String label() {
        return label;
    }

    /** 식에 쓰는 글자. */
    public String symbol() {
        return symbol;
    }

    public Arity arity() {
        return arity;
    }

    public boolean supports(FieldType type) {
        return types.contains(type);
    }

    /**
     * 이 개수의 값을 받을 수 있는가.
     *
     * <p>값이 0개인 {@code IN} 은 SQL 에서 {@code IN ()} 이 되어 문법 오류다. 「전부」 는 조건을
     * 두지 않는 것으로 표현하지, 빈 목록으로 표현하지 않는다.
     */
    public boolean acceptsCount(int count) {
        return switch (arity) {
            case NONE -> count == 0;
            case ONE -> count == 1;
            case TWO -> count == 2;
            case AT_LEAST_ONE -> count >= 1;
        };
    }

    private static String strip(String value) {
        return value.toUpperCase(Locale.ROOT).replace(" ", "");
    }

    /** 그 타입에 쓸 수 있는 연산자. 화면이 드롭다운을 그리는 데 쓴다. */
    public static List<FilterOperator> forType(FieldType type) {
        return Arrays.stream(values()).filter(op -> op.supports(type)).toList();
    }

    /**
     * 식에서 읽은 글자로 연산자를 찾는다.
     *
     * <p>한글과 기호를 함께 받는 이유는, 이 화면을 쓰는 사람이 개발자가 아니기 때문이다.
     * 「포함」 으로 쓰든 {@code CONTAINS} 로 쓰든 같은 연산자가 된다.
     */
    public static Optional<FilterOperator> ofSymbol(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String noSpace = token.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        for (FilterOperator op : values()) {
            // 이름·기호·화면 이름을 모두 받는다. 화면에서 「사이」 를 보고 식에 그대로 적었는데
            // 안 읽히면, 사람은 무엇이 틀렸는지 알 수 없다.
            if (op.name().equals(noSpace)
                    || strip(op.symbol).equals(noSpace)
                    || strip(op.label).equals(noSpace)) {
                return Optional.of(op);
            }
        }
        // 흔한 다른 표기. 늘리기 쉬우라고 여기 모아 둔다.
        return switch (noSpace) {
            case "!=", "<>" -> Optional.of(NE);
            case "==" -> Optional.of(EQ);
            case "≥" -> Optional.of(GTE);
            case "≤" -> Optional.of(LTE);
            default -> Optional.empty();
        };
    }
}
