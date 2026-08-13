package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.suggest.FieldMappingMemoryService;
import kr.suhsaechan.palim.connector.suggest.FieldSuggester;
import kr.suhsaechan.palim.connector.suggest.FieldSuggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 한 번 연결한 것을 기억해 다음에 먼저 골라 주는가.
 *
 * <p>사전은 <b>우리가 아는 이름만</b> 잡는다. 다음에 붙일 시스템의 칸 이름은 알 수 없으므로,
 * 그것만으로는 새 원천이 늘 때마다 사람이 처음부터 고르게 된다.
 *
 * <p>이 기억이 있으면 우리가 모르는 이름도 <b>한 번만 손대면 그 뒤로는 자동</b>이다. 그래서
 * 네 근거 중 이것이 확장성의 핵심이다.
 */
class FieldMappingMemoryIntegrationTest extends IntegrationTest {

    private static final String MODEL = "std_stock_snapshot";

    @Autowired private FieldSuggester suggester;
    @Autowired private FieldMappingMemoryService memories;

    private FieldSuggestion pick(List<FieldSuggestion> all, String sourceField) {
        return all.stream().filter(s -> s.sourceField().equals(sourceField))
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("우리가 모르는 이름도 한 번 연결하면 다음부터 추천한다")
    void 기억하면_다음에_추천한다() {
        // 사전에 없는 이름이다. 값도 숫자라 값 모양(40)만 걸려 임계를 넘지 못한다.
        String unknown = "STOCK_BALANCE_AMT";
        List<Map<String, Object>> samples = List.of(Map.of(unknown, "500"));

        assertThat(pick(suggester.suggest(List.of(unknown), samples, MODEL), unknown))
                .as("처음에는 근거가 약해 고르지 않는다")
                .isNull();

        // 사람이 한 번 연결했다 — 매핑을 «확정» 할 때만 기억한다.
        memories.remember(MODEL, Map.of(unknown, "quantity"));

        FieldSuggestion after = pick(suggester.suggest(List.of(unknown), samples, MODEL), unknown);
        assertThat(after)
                .as("한 번 손댔으면 그 뒤로는 시스템이 먼저 골라 둔다")
                .isNotNull();
        assertThat(after.targetFieldKey()).isEqualTo("quantity");
        assertThat(after.reasons())
                .as("왜 골랐는지 사람이 알아야 확인할 마음이 든다")
                .anyMatch(reason -> reason.contains("연결"));
    }

    @Test
    @DisplayName("같은 연결을 반복하면 확신이 커진다")
    void 반복하면_점수가_오른다() {
        String field = "WMS_QTY_ON_HAND";
        List<Map<String, Object>> samples = List.of(Map.of(field, "12"));

        memories.remember(MODEL, Map.of(field, "quantity"));
        int first = pick(suggester.suggest(List.of(field), samples, MODEL), field).score();

        memories.remember(MODEL, Map.of(field, "quantity"));
        memories.remember(MODEL, Map.of(field, "quantity"));
        int later = pick(suggester.suggest(List.of(field), samples, MODEL), field).score();

        assertThat(later)
                .as("반복된 판단에 무게를 준다")
                .isGreaterThan(first);
    }

    /**
     * 대소문자·밑줄만 다른 이름을 다른 것으로 보면 기억이 쌓이지 않는다. 원천마다 표기가
     * 제각각이라 그대로 두면 같은 칸을 매번 새로 배우게 된다.
     */
    @Test
    @DisplayName("표기가 달라도 같은 이름으로 본다")
    void 표기_차이를_흡수한다() {
        memories.remember(MODEL, Map.of("ITEM_TOTAL_QTY", "quantity"));

        FieldSuggestion found = pick(
                suggester.suggest(List.of("item total qty"),
                        List.of(Map.of("item total qty", "7")), MODEL),
                "item total qty");

        assertThat(found).isNotNull();
        assertThat(found.targetFieldKey()).isEqualTo("quantity");
    }

    /**
     * 화면에서 고르는 중에는 기억하지 않는다. 고민하며 이것저것 눌러 본 것까지 학습하면
     * 기억이 오염되고, 그 뒤로 잘못된 추천이 계속 나온다.
     */
    @Test
    @DisplayName("연결하지 않은 칸은 기억하지 않는다")
    void 빈_연결은_기억하지_않는다() {
        String field = "SOME_UNUSED_COL";

        memories.remember(MODEL, Map.of(field, ""));

        assertThat(pick(suggester.suggest(List.of(field),
                List.of(Map.of(field, "값")), MODEL), field))
                .as("연결하지 않은 것을 기억하면 빈 칸이 추천으로 되살아난다")
                .isNull();
    }
}
