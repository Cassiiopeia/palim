package kr.suhsaechan.palim.reconcile.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 조건을 SQL 로 만든다.
 *
 * <p><b>사용자 문자열이 SQL 에 이어붙는 자리가 없다.</b> 식별자는 카탈로그에서만 나오고 값은
 * 전부 바인딩 파라미터로 간다. 이 시험이 지키는 것이 그 성질이다.
 */
class FilterSpecTest {

    private static final Instant AS_OF = Instant.parse("2026-08-22T03:00:00Z");

    private static FilterNode.Compare compare(String key, FilterOperator op, String... values) {
        return new FilterNode.Compare(FieldCatalog.find(key).orElseThrow(), op, List.of(values));
    }

    @Test
    @DisplayName("조건이 없으면 빈 문자열이다 — IN () 은 문법 오류라 「전부」 를 빈 목록으로 쓸 수 없다")
    void allProducesNothing() {
        FilterSpec spec = FilterSpec.all();

        assertThat(spec.isAll()).isTrue();
        assertThat(spec.sqlAnd("s", "f", AS_OF)).isEmpty();
        assertThat(spec.params("f", AS_OF)).isEmpty();
    }

    @Test
    @DisplayName("값은 언제나 바인딩으로 간다 — SQL 문자열에 값이 나타나지 않는다")
    void bindsValues() {
        FilterSpec spec = new FilterSpec(compare("warehouse_code", FilterOperator.IN,
                "01", "02"));

        FilterSpec.Compiled compiled = spec.compile("s", "f", AS_OF);

        assertThat(compiled.sql()).isEqualTo(" AND (s.warehouse_code IN (:f0, :f1))");
        assertThat(compiled.params()).containsExactly(entry("f0", "01"), entry("f1", "02"));
    }

    @Test
    @DisplayName("주입을 노린 값도 값일 뿐이다 — 바인딩되므로 SQL 이 되지 않는다")
    void injectionPayloadStaysAValue() {
        String payload = "01'); DROP TABLE std_stock_snapshot; --";
        FilterSpec spec = new FilterSpec(compare("warehouse_code", FilterOperator.EQ, payload));

        FilterSpec.Compiled compiled = spec.compile("s", "f", AS_OF);

        assertThat(compiled.sql()).isEqualTo(" AND (s.warehouse_code = :f0)");
        assertThat(compiled.sql()).doesNotContain("DROP").doesNotContain("--");
        assertThat(compiled.params()).containsEntry("f0", payload);
    }

    @Test
    @DisplayName("숫자 칸은 숫자로, 날짜 칸은 날짜로 바인딩한다")
    void bindsTypedValues() {
        FilterSpec numeric = new FilterSpec(
                compare("base_quantity", FilterOperator.GTE, "100"));
        assertThat(numeric.params("f", AS_OF)).containsEntry("f0", new BigDecimal("100"));

        FilterSpec dated = new FilterSpec(
                compare("expiry_date", FilterOperator.GTE, "오늘"));
        assertThat(dated.params("f", AS_OF))
                .containsEntry("f0", LocalDate.of(2026, 8, 22));
    }

    @Test
    @DisplayName("상대 날짜는 회차 기준 시각으로 풀린다 — 하루 뒤에 돌리면 범위도 하루 밀린다")
    void relativeDateFollowsRunTime() {
        FilterSpec spec = new FilterSpec(compare("expiry_date", FilterOperator.GTE, "오늘+30"));

        assertThat(spec.params("f", AS_OF))
                .containsEntry("f0", LocalDate.of(2026, 9, 21));
        assertThat(spec.params("f", AS_OF.plus(Duration.ofDays(1))))
                .containsEntry("f0", LocalDate.of(2026, 9, 22));
    }

    @Test
    @DisplayName("값이 없는 연산자는 바인딩이 없다")
    void noValueOperators() {
        FilterSpec spec = new FilterSpec(compare("lot_code", FilterOperator.IS_EMPTY));

        FilterSpec.Compiled compiled = spec.compile("s", "f", AS_OF);

        // lot_code 는 NOT NULL DEFAULT '' 다. 「비었음」 은 NULL 과 빈 문자열을 함께 본다.
        assertThat(compiled.sql()).isEqualTo(" AND (coalesce(s.lot_code, '') = '')");
        assertThat(compiled.params()).isEmpty();
    }

    @Test
    @DisplayName("AND · OR · 괄호가 중첩된다")
    void nestsBooleans() {
        FilterNode node = new FilterNode.And(List.of(
                new FilterNode.Or(List.of(
                        compare("warehouse_code", FilterOperator.EQ, "01"),
                        compare("warehouse_code", FilterOperator.EQ, "02"))),
                new FilterNode.Not(compare("quality_status", FilterOperator.EQ, "불량"))));

        FilterSpec.Compiled compiled = new FilterSpec(node).compile("s", "f", AS_OF);

        assertThat(compiled.sql()).isEqualTo(
                " AND ((s.warehouse_code = :f0 OR s.warehouse_code = :f1)"
                        + " AND NOT (s.quality_status = :f2))");
        assertThat(compiled.params()).hasSize(3);
    }

    @Test
    @DisplayName("접두어가 다르면 바인딩 이름이 겹치지 않는다 — 좌·우를 한 쿼리에 거는 자리가 있다")
    void prefixKeepsNamesApart() {
        FilterSpec left = new FilterSpec(compare("warehouse_code", FilterOperator.EQ, "01"));
        FilterSpec right = new FilterSpec(compare("warehouse_code", FilterOperator.EQ, "99"));

        assertThat(left.params("lf", AS_OF)).containsOnlyKeys("lf0");
        assertThat(right.params("rf", AS_OF)).containsOnlyKeys("rf0");
    }

    @Test
    @DisplayName("두 번 컴파일해도 같은 이름·같은 값이 나온다 — sqlAnd 와 params 를 따로 부른다")
    void compileIsDeterministic() {
        FilterSpec spec = new FilterSpec(new FilterNode.And(List.of(
                compare("warehouse_code", FilterOperator.IN, "01", "02"),
                compare("quality_status", FilterOperator.EQ, "정상"))));

        assertThat(spec.sqlAnd("s", "f", AS_OF)).isEqualTo(spec.sqlAnd("s", "f", AS_OF));
        assertThat(spec.params("f", AS_OF)).isEqualTo(spec.params("f", AS_OF));
        assertThat(spec.sqlAnd("s", "f", AS_OF)).contains(":f0", ":f1", ":f2");
    }

    @Test
    @DisplayName("사이는 두 값을 쓴다")
    void betweenUsesTwoValues() {
        FilterSpec spec = new FilterSpec(
                compare("expiry_date", FilterOperator.BETWEEN, "오늘", "오늘+30"));

        assertThat(spec.sqlAnd("s", "f", AS_OF))
                .isEqualTo(" AND (s.expiry_date BETWEEN :f0 AND :f1)");
    }

    @Test
    @DisplayName("몇 개인지·얼마나 깊은지를 센다 — 폭주를 막는 쪽이 이 값을 본다")
    void countsSize() {
        FilterNode node = new FilterNode.And(List.of(
                compare("warehouse_code", FilterOperator.EQ, "01"),
                new FilterNode.Or(List.of(
                        compare("lot_code", FilterOperator.IS_EMPTY),
                        compare("zone_code", FilterOperator.IS_EMPTY)))));

        assertThat(node.nodeCount()).isEqualTo(5);
        assertThat(node.depth()).isEqualTo(3);
    }

    @Test
    @DisplayName("포함은 LIKE 로 가되 값의 % 와 _ 를 글자로 다룬다")
    void containsEscapesWildcards() {
        FilterSpec spec = new FilterSpec(
                compare("raw_item_name", FilterOperator.CONTAINS, "50%_A"));

        FilterSpec.Compiled compiled = spec.compile("s", "f", AS_OF);

        assertThat(compiled.sql())
                .isEqualTo(" AND (s.raw_item_name LIKE :f0 ESCAPE '\\')");
        assertThat(compiled.params()).containsEntry("f0", "%50\\%\\_A%");
    }
}
