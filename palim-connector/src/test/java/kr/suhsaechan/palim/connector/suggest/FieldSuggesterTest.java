package kr.suhsaechan.palim.connector.suggest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.connector.model.StockSnapshotFields;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 원천 칸을 어느 표준 항목에 연결할지 <b>미리 골라 둔다</b>.
 *
 * <p>빈 화면을 주면 사람은 표준 항목 스물아홉 개를 하나씩 훑으며 매번 처음부터 고른다. 원천이
 * 늘 때마다 그 일이 반복되고, 시스템이 열 개가 되면 열 번 한다.
 *
 * <p><b>추천일 뿐 확정이 아니다.</b> 틀린 추천은 빈 칸보다 나쁘지 않다 — 어차피 사람이 확인해야
 * 하고, 맞은 것은 그냥 두면 된다. 다만 근거가 약할 때는 찍지 않고 비워 둔다.
 */
class FieldSuggesterTest {

    private final FieldSuggester suggester = new FieldSuggester(
            List.of(new StockSnapshotFields()),
            List.of(new AliasSource(), new NamePatternSource(), new ValueShapeSource()));

    private static final String MODEL = "std_stock_snapshot";

    private FieldSuggestion pick(List<FieldSuggestion> all, String sourceField) {
        return all.stream().filter(s -> s.sourceField().equals(sourceField))
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("사전에 적어 둔 이름을 알아본다")
    void 사전에_있는_이름을_고른다() {
        List<FieldSuggestion> result = suggester.suggest(
                List.of("BAL_QTY", "PROD_CD", "WH_DES"),
                List.of(Map.of("BAL_QTY", "112", "PROD_CD", "A0001", "WH_DES", "본사 창고")),
                MODEL);

        assertThat(pick(result, "BAL_QTY").targetFieldKey()).isEqualTo("quantity");
        assertThat(pick(result, "PROD_CD").targetFieldKey()).isEqualTo("item_ref");
        assertThat(pick(result, "WH_DES").targetFieldKey()).isEqualTo("warehouse_name");
    }

    /**
     * 사전은 우리가 아는 이름만 잡는다. 다음에 붙일 시스템의 칸 이름은 알 수 없으므로,
     * 이름에 든 낱말로도 짐작할 수 있어야 한다.
     */
    @Test
    @DisplayName("사전에 없어도 이름에 든 낱말로 짐작한다")
    void 이름_규칙으로_짐작한다() {
        List<FieldSuggestion> result = suggester.suggest(
                List.of("CURRENT_STOCK_QUANTITY"),
                List.of(Map.of("CURRENT_STOCK_QUANTITY", "500")),
                MODEL);

        assertThat(pick(result, "CURRENT_STOCK_QUANTITY"))
                .as("QUANTITY 가 들어 있으면 수량 후보다")
                .isNotNull();
        assertThat(pick(result, "CURRENT_STOCK_QUANTITY").targetFieldKey()).isEqualTo("quantity");
    }

    /**
     * 찍어서 틀리느니 비워 두는 편이 낫다. 사람은 빈 칸은 채우지만, 그럴듯하게 채워진 칸은
     * 확인하지 않고 넘어간다.
     */
    @Test
    @DisplayName("근거가 약하면 고르지 않는다")
    void 근거가_약하면_비운다() {
        List<FieldSuggestion> result = suggester.suggest(
                List.of("COL_017"),
                List.of(Map.of("COL_017", "알 수 없는 값입니다")),
                MODEL);

        assertThat(pick(result, "COL_017"))
                .as("이름에도 값에도 단서가 없으면 사람이 직접 고른다")
                .isNull();
    }

    @Test
    @DisplayName("왜 골랐는지 근거를 남긴다")
    void 근거를_남긴다() {
        List<FieldSuggestion> result = suggester.suggest(
                List.of("BAL_QTY"), List.of(Map.of("BAL_QTY", "112")), MODEL);

        assertThat(pick(result, "BAL_QTY").reasons())
                .as("«왜 이걸 골랐는지» 를 화면이 말할 수 있어야 한다")
                .isNotEmpty();
    }

    /**
     * 한 표준 항목에 두 칸이 붙으면 나중 것이 앞의 것을 덮어써 조용히 자료가 어긋난다.
     * 점수가 높은 쪽만 남긴다.
     */
    @Test
    @DisplayName("같은 항목을 두 칸이 노리면 점수가 높은 쪽만 남긴다")
    void 한_항목에는_한_칸만() {
        List<FieldSuggestion> result = suggester.suggest(
                List.of("BAL_QTY", "STOCK_QTY"),
                List.of(Map.of("BAL_QTY", "112", "STOCK_QTY", "112")),
                MODEL);

        long quantityCount = result.stream()
                .filter(s -> s.targetFieldKey().equals("quantity")).count();
        assertThat(quantityCount).isEqualTo(1);
    }
}
