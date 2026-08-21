package kr.suhsaechan.palim.reconcile.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import kr.suhsaechan.palim.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 줄로 안 되는 조건을 글로 쓴다.
 *
 * <p>한글 연산자를 함께 받는 이유는 이 화면을 쓰는 사람이 개발자가 아니기 때문이다. 「그리고」
 * 로 쓰든 {@code AND} 로 쓰든 같은 나무가 된다.
 */
class ExpressionParserTest {

    private static final Instant AS_OF = Instant.parse("2026-08-22T03:00:00Z");

    private static String sql(String text) {
        return new FilterSpec(ExpressionParser.parse(text)).sqlAnd("s", "f", AS_OF);
    }

    @Test
    @DisplayName("칸을 넘는 OR 을 괄호로 묶는다 — 줄로는 쓸 수 없던 조건이다")
    void parsesOrAcrossFields() {
        assertThat(sql("(창고 = '01' 또는 창고 = '02') 그리고 품질상태 ≠ '불량'"))
                .isEqualTo(" AND ((s.warehouse_code = :f0 OR s.warehouse_code = :f1)"
                        + " AND s.quality_status <> :f2)");
    }

    @Test
    @DisplayName("영문 연산자도 같은 나무가 된다")
    void acceptsEnglishOperators() {
        assertThat(sql("warehouse_code = '01' AND quality_status = '정상'"))
                .isEqualTo(sql("창고 = '01' 그리고 품질상태 = '정상'"));
    }

    @Test
    @DisplayName("IN · BETWEEN · 비었음을 읽는다")
    void parsesMultiValueOperators() {
        assertThat(sql("창고 IN ('01', '02')"))
                .isEqualTo(" AND (s.warehouse_code IN (:f0, :f1))");
        assertThat(sql("유통기한 사이 오늘 AND 오늘+30"))
                .isEqualTo(" AND (s.expiry_date BETWEEN :f0 AND :f1)");
        assertThat(sql("로트 비었음"))
                .isEqualTo(" AND (coalesce(s.lot_code, '') = '')");
    }

    @Test
    @DisplayName("이것 빼고는 두 낱말짜리 연산자다 — NOT 만 읽고 IN 을 흘리면 안 된다")
    void parsesTwoWordOperator() {
        assertThat(sql("창고 NOT IN ('01')"))
                .isEqualTo(" AND (s.warehouse_code NOT IN (:f0))");
    }

    @Test
    @DisplayName("아님으로 통째로 뒤집는다")
    void parsesNot() {
        assertThat(sql("아님 (창고 = '01')"))
                .isEqualTo(" AND (NOT (s.warehouse_code = :f0))");
    }

    @Test
    @DisplayName("원천 고유 칸도 식에서 걸 수 있다")
    void parsesAttributeField() {
        assertThat(sql("attributes.재고구분 = '정상'"))
                .isEqualTo(" AND (s.attributes->>'재고구분' = :f0)");
    }

    @Test
    @DisplayName("AND 가 OR 보다 세게 묶인다 — 괄호 없이 쓴 뜻이 상식과 맞아야 한다")
    void andBindsTighterThanOr() {
        assertThat(sql("창고 = '01' 또는 창고 = '02' 그리고 품질상태 = '정상'"))
                .isEqualTo(" AND (s.warehouse_code = :f0"
                        + " OR (s.warehouse_code = :f1 AND s.quality_status = :f2))");
    }

    @Test
    @DisplayName("빈 글은 아무것도 거르지 않는다")
    void blankMeansAll() {
        assertThat(ExpressionParser.parse("").isAll()).isTrue();
        assertThat(ExpressionParser.parse("   ").isAll()).isTrue();
        assertThat(ExpressionParser.parse(null).isAll()).isTrue();
    }

    @Test
    @DisplayName("칸에 맞지 않는 연산자는 거부한다 — 글 칸에 「사이」 는 쓸 수 없다")
    void rejectsOperatorTypeMismatch() {
        assertThatThrownBy(() -> ExpressionParser.parse("창고 사이 1 그리고 2"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("연산자가 요구하는 값 개수를 어기면 거부한다")
    void rejectsWrongValueCount() {
        assertThatThrownBy(() -> ExpressionParser.parse("유통기한 사이 오늘"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ExpressionParser.parse("창고 IN ()"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("읽을 수 없는 날짜는 거부한다 — 도는 순간까지 미루지 않는다")
    void rejectsBadDate() {
        assertThatThrownBy(() -> ExpressionParser.parse("유통기한 이후 '어제'"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("따옴표가 닫히지 않으면 거부한다")
    void rejectsUnclosedQuote() {
        assertThatThrownBy(() -> ExpressionParser.parse("창고 = '01"))
                .isInstanceOf(BusinessException.class);
    }
}
