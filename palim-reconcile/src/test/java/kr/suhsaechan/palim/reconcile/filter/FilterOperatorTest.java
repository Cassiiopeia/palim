package kr.suhsaechan.palim.reconcile.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 연산자는 타입이 정한다.
 *
 * <p>부정형을 짝으로 두는 이유가 중요하다. {@code IN} 만 있고 {@code NOT_IN} 이 없으면
 * 「불량만 빼고 전부」 를 하려고 나머지 값을 전부 체크해야 하는데, <b>값이 늘어나는 날 조건이
 * 조용히 낡는다</b> — 새로 생긴 값이 빠진 채로 돈다.
 */
class FilterOperatorTest {

    @Test
    @DisplayName("글 칸에는 담기·포함·비었음이 있고 크기 비교는 없다")
    void textOperators() {
        assertThat(FilterOperator.forType(FieldType.TEXT))
                .contains(FilterOperator.IN, FilterOperator.NOT_IN,
                        FilterOperator.CONTAINS, FilterOperator.NOT_CONTAINS,
                        FilterOperator.STARTS_WITH, FilterOperator.ENDS_WITH,
                        FilterOperator.MATCHES,
                        FilterOperator.IS_EMPTY, FilterOperator.IS_NOT_EMPTY)
                .doesNotContain(FilterOperator.GT, FilterOperator.BETWEEN);
    }

    @Test
    @DisplayName("날짜·숫자 칸에는 크기 비교와 사이가 있다")
    void comparableOperators() {
        assertThat(FilterOperator.forType(FieldType.DATE))
                .contains(FilterOperator.GTE, FilterOperator.LTE,
                        FilterOperator.BETWEEN, FilterOperator.NOT_BETWEEN)
                .doesNotContain(FilterOperator.CONTAINS);
        assertThat(FilterOperator.forType(FieldType.NUMBER))
                .contains(FilterOperator.GT, FilterOperator.NE);
    }

    @Test
    @DisplayName("모든 부정형에 짝이 있다 — 하나라도 빠지면 값이 늘 때 조건이 낡는다")
    void negationsArePaired() {
        for (FieldType type : FieldType.values()) {
            var ops = FilterOperator.forType(type);
            if (ops.contains(FilterOperator.IN)) {
                assertThat(ops).contains(FilterOperator.NOT_IN);
            }
            if (ops.contains(FilterOperator.CONTAINS)) {
                assertThat(ops).contains(FilterOperator.NOT_CONTAINS);
            }
            if (ops.contains(FilterOperator.BETWEEN)) {
                assertThat(ops).contains(FilterOperator.NOT_BETWEEN);
            }
            if (ops.contains(FilterOperator.IS_EMPTY)) {
                assertThat(ops).contains(FilterOperator.IS_NOT_EMPTY);
            }
        }
    }

    @Test
    @DisplayName("값 개수는 연산자가 정한다 — 화면과 서버가 같은 규칙을 한 곳에서 읽는다")
    void valueCounts() {
        assertThat(FilterOperator.IS_EMPTY.acceptsCount(0)).isTrue();
        assertThat(FilterOperator.IS_EMPTY.acceptsCount(1)).isFalse();

        assertThat(FilterOperator.EQ.acceptsCount(1)).isTrue();
        assertThat(FilterOperator.EQ.acceptsCount(0)).isFalse();
        assertThat(FilterOperator.EQ.acceptsCount(2)).isFalse();

        assertThat(FilterOperator.BETWEEN.acceptsCount(2)).isTrue();
        assertThat(FilterOperator.BETWEEN.acceptsCount(1)).isFalse();

        assertThat(FilterOperator.IN.acceptsCount(1)).isTrue();
        assertThat(FilterOperator.IN.acceptsCount(5)).isTrue();
        // 값이 0개인 IN 은 SQL 에서 IN () 이 되어 문법 오류다. 저장에서 막는다.
        assertThat(FilterOperator.IN.acceptsCount(0)).isFalse();
    }

    @Test
    @DisplayName("식에 쓰는 글자로 연산자를 찾는다 — 한글과 기호를 함께 받는다")
    void findsBySymbol() {
        assertThat(FilterOperator.ofSymbol("=")).contains(FilterOperator.EQ);
        assertThat(FilterOperator.ofSymbol("!=")).contains(FilterOperator.NE);
        assertThat(FilterOperator.ofSymbol("≠")).contains(FilterOperator.NE);
        assertThat(FilterOperator.ofSymbol(">=")).contains(FilterOperator.GTE);
        assertThat(FilterOperator.ofSymbol("포함")).contains(FilterOperator.CONTAINS);
        assertThat(FilterOperator.ofSymbol("CONTAINS")).contains(FilterOperator.CONTAINS);
        assertThat(FilterOperator.ofSymbol("contains")).contains(FilterOperator.CONTAINS);
        assertThat(FilterOperator.ofSymbol("DROP")).isEmpty();
    }

    @Test
    @DisplayName("불리언 칸에는 참·거짓·비었음·값있음만 있다")
    void boolOperators() {
        assertThat(FilterOperator.forType(FieldType.BOOL))
                .containsExactlyInAnyOrder(FilterOperator.IS_TRUE, FilterOperator.IS_FALSE,
                        FilterOperator.IS_EMPTY, FilterOperator.IS_NOT_EMPTY);
    }
}
