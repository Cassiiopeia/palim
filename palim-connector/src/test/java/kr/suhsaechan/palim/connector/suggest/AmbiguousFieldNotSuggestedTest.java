package kr.suhsaechan.palim.connector.suggest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.suhsaechan.palim.connector.model.StockSnapshotFields;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 뜻이 애매한 칸에는 <b>추천을 찍지 않는다</b>.
 *
 * <p>물류 시스템의 칸 이름은 이름만 보고는 무엇인지 알 수 없다 — {@code key} 가 품목코드인지
 * 내부 일련번호인지, {@code stock_normal} 이 재고 수량인지 상태값인지 알 방법이 없다. 실제로
 * 그 시스템을 열어 값을 본 사람만 안다.
 *
 * <p>그래서 <b>비워 둔다.</b> 사람은 빈 칸은 채우지만 <b>그럴듯하게 채워진 칸은 확인하지 않고
 * 넘어간다.</b> 틀린 추천은 없는 추천보다 나쁘다 — 잘못 담긴 자료는 오류로도 남지 않고, 대조
 * 결과가 이상해진 뒤에야 몇 주 만에 드러난다.
 *
 * <p>이 테스트는 「추천이 하나도 안 붙어 불편하다」 는 이유로 사전에 짐작을 채워 넣는 것을
 * 막는다. 채워야 할 곳은 사전이 아니라 <b>화면의 값 미리보기</b> 다 — 실제 값을 보여주면
 * 사람이 스스로 판단한다. 한 번 확정한 연결은 {@link FieldMappingMemory} 가 기억한다.
 */
class AmbiguousFieldNotSuggestedTest {

    private final FieldSuggester suggester = new FieldSuggester(
            List.of(new StockSnapshotFields()),
            List.of(new AliasSource(), new NamePatternSource(), new ValueShapeSource()));

    /** 물류 시스템이 실제로 주는 칸. 어느 것도 이름만으로는 뜻을 알 수 없다. */
    private static final List<String> AMBIGUOUS_FIELDS = List.of(
            "key", "product_id", "stock_normal", "stock_alarm1",
            "stock_info_st_0_wh_1", "stock_in_standby", "options", "period_alarm");

    private static final List<Map<String, Object>> ROWS = List.of(
            Map.of("key", "00094", "product_id", "00094", "stock_normal", "425",
                    "stock_alarm1", "0", "stock_info_st_0_wh_1", "425",
                    "stock_in_standby", "0", "options", "", "period_alarm", "0"));

    @Test
    @DisplayName("뜻이 애매한 칸에는 추천을 찍지 않는다")
    void 애매하면_비워_둔다() {
        Map<String, String> picked = suggester.suggest(AMBIGUOUS_FIELDS, ROWS,
                        "std_stock_snapshot").stream()
                .collect(Collectors.toMap(FieldSuggestion::targetFieldKey,
                        FieldSuggestion::sourceField));

        assertThat(picked)
                .as("틀린 추천은 없는 추천보다 나쁘다 — 채워진 칸은 확인 없이 넘어간다")
                .doesNotContainKeys("item_ref", "quantity", "raw_item_name", "product_key");
    }

    /**
     * 확실한 이름에는 <b>여전히 붙어야</b> 한다.
     *
     * <p>애매한 것을 안 찍는 것과 아는 것도 안 찍는 것은 다르다. 뒤쪽이면 추천 기능 자체가
     * 없는 것과 같다.
     */
    @Test
    @DisplayName("뜻이 분명한 이름에는 그대로 추천이 붙는다")
    void 확실하면_찍는다() {
        Map<String, String> picked = suggester.suggest(
                        List.of("ITEM_CD", "BAL_QTY", "PROD_DES"),
                        List.of(Map.of("ITEM_CD", "A-1", "BAL_QTY", "10",
                                "PROD_DES", "제품A")),
                        "std_stock_snapshot").stream()
                .collect(Collectors.toMap(FieldSuggestion::targetFieldKey,
                        FieldSuggestion::sourceField));

        assertThat(picked)
                .containsEntry("item_ref", "ITEM_CD")
                .containsEntry("quantity", "BAL_QTY");
    }
}
