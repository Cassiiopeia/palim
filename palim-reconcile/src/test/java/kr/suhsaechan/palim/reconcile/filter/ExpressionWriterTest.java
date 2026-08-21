package kr.suhsaechan.palim.reconcile.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 나무를 글로 되돌린다.
 *
 * <p>조건 줄과 식이 한 나무로 모이므로 <b>「지금 조건을 식으로 보기」 가 공짜로 나온다.</b>
 * 사람이 조건 줄로 시작해 식을 배우는 길이 되고, 「내가 고른 것이 무슨 뜻인지」 를 확인하는
 * 길도 된다.
 */
class ExpressionWriterTest {

    private static final Instant AS_OF = Instant.parse("2026-08-22T03:00:00Z");

    private static FilterNode.Compare compare(String key, FilterOperator op, String... values) {
        return new FilterNode.Compare(FieldCatalog.find(key).orElseThrow(), op, List.of(values));
    }

    @Test
    @DisplayName("조건 줄을 글로 되돌린다")
    void writesRows() {
        FilterNode node = new FilterNode.And(List.of(
                compare("warehouse_code", FilterOperator.IN, "01", "02"),
                compare("quality_status", FilterOperator.NOT_IN, "불량")));

        assertThat(ExpressionWriter.write(node))
                .isEqualTo("창고 IN ('01', '02') 그리고 품질상태 NOT IN ('불량')");
    }

    @Test
    @DisplayName("되돌린 글을 다시 읽으면 같은 SQL 이 나온다 — 두 입구가 한 나무로 모인다")
    void roundTrips() {
        FilterNode original = new FilterNode.And(List.of(
                new FilterNode.Or(List.of(
                        compare("warehouse_code", FilterOperator.EQ, "01"),
                        compare("warehouse_code", FilterOperator.EQ, "02"))),
                compare("expiry_date", FilterOperator.GTE, "오늘+30")));

        FilterNode reparsed = ExpressionParser.parse(ExpressionWriter.write(original));

        assertThat(new FilterSpec(reparsed).compile("s", "f", AS_OF))
                .isEqualTo(new FilterSpec(original).compile("s", "f", AS_OF));
    }

    @Test
    @DisplayName("값이 없는 연산자도 되읽힌다")
    void roundTripsNoValueOperator() {
        FilterNode original = compare("lot_code", FilterOperator.IS_EMPTY);

        assertThat(ExpressionParser.parse(ExpressionWriter.write(original)))
                .isEqualTo(original);
    }

    @Test
    @DisplayName("사이도 되읽힌다")
    void roundTripsBetween() {
        FilterNode original = compare("expiry_date", FilterOperator.BETWEEN, "오늘", "오늘+30");

        assertThat(ExpressionParser.parse(ExpressionWriter.write(original)))
                .isEqualTo(original);
    }

    @Test
    @DisplayName("조건이 없으면 「전체」 라고 말한다")
    void writesAll() {
        assertThat(ExpressionWriter.write(FilterNode.ALL)).isEqualTo("전체");
        assertThat(FilterSpec.all().describe()).isEqualTo("전체");
    }

    @Test
    @DisplayName("값 안의 작은따옴표는 두 번 적어 되읽을 수 있게 한다")
    void escapesQuotes() {
        String written = ExpressionWriter.write(
                compare("raw_item_name", FilterOperator.CONTAINS, "a'b"));

        assertThat(written).isEqualTo("원본품명 포함 'a''b'");
        assertThat(ExpressionParser.parse(written)).isEqualTo(
                compare("raw_item_name", FilterOperator.CONTAINS, "a'b"));
    }
}
