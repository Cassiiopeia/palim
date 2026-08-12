package kr.suhsaechan.palim.connector.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 자연키 생성.
 *
 * <p>"무엇이 같으면 같은 행인가"를 문자열 하나로 만든다. 이 값이 UPSERT 의 기준이므로
 * <b>서로 다른 행이 같은 키가 되는 순간 데이터가 조용히 사라진다.</b>
 */
class NaturalKeyBuilderTest {

    private final NaturalKeyBuilder builder = new NaturalKeyBuilder();

    @Test
    @DisplayName("키 필드 값을 순서대로 잇는다")
    void 키를_조합한다() {
        Map<String, Object> values = Map.of(
                "source", "ERP", "item_ref", "A-001", "warehouse_code", "W1");

        String key = builder.build(values, List.of("source", "item_ref", "warehouse_code"));

        assertThat(key).contains("ERP", "A-001", "W1");
    }

    @Test
    @DisplayName("필드 순서가 다르면 다른 키다")
    void 순서가_의미를_갖는다() {
        Map<String, Object> values = Map.of("a", "1", "b", "2");

        String forward = builder.build(values, List.of("a", "b"));
        String backward = builder.build(values, List.of("b", "a"));

        assertThat(forward).isNotEqualTo(backward);
    }

    @Test
    @DisplayName("값이 없는 키 필드도 자리를 지킨다")
    void 빈_값도_자리를_지킨다() {
        Map<String, Object> withLot = new HashMap<>();
        withLot.put("source", "ERP");
        withLot.put("lot", "L1");

        Map<String, Object> withoutLot = new HashMap<>();
        withoutLot.put("source", "ERPL1");
        withoutLot.put("lot", null);

        String first = builder.build(withLot, List.of("source", "lot"));
        String second = builder.build(withoutLot, List.of("source", "lot"));

        assertThat(first)
                .as("자리가 밀리면 'ERP'+'L1' 과 'ERPL1'+'' 이 같은 키가 된다")
                .isNotEqualTo(second);
    }

    @Test
    @DisplayName("구분자가 값에 들어 있어도 충돌하지 않는다")
    void 구분자_충돌이_없다() {
        Map<String, Object> first = Map.of("a", "X|Y", "b", "Z");
        Map<String, Object> second = Map.of("a", "X", "b", "Y|Z");

        assertThat(builder.build(first, List.of("a", "b")))
                .isNotEqualTo(builder.build(second, List.of("a", "b")));
    }

    @Test
    @DisplayName("같은 값이면 항상 같은 키다 — 재실행이 중복을 만들면 안 된다")
    void 같은_값은_같은_키() {
        Map<String, Object> values = Map.of("source", "ERP", "item_ref", "A-001");

        assertThat(builder.build(values, List.of("source", "item_ref")))
                .isEqualTo(builder.build(values, List.of("source", "item_ref")));
    }

    @Test
    @DisplayName("키 필드가 전부 비면 실패시킨다")
    void 전부_비면_실패() {
        Map<String, Object> values = new HashMap<>();
        values.put("source", null);
        values.put("item_ref", "");

        assertThatThrownBy(() -> builder.build(values, List.of("source", "item_ref")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NATURAL_KEY_INCOMPLETE);
    }

    @Test
    @DisplayName("키 필드 목록이 비면 실패시킨다 — 중복 판정 기준이 없다는 뜻이다")
    void 키_필드가_없으면_실패() {
        assertThatThrownBy(() -> builder.build(Map.of("a", "1"), List.of()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NATURAL_KEY_INCOMPLETE);
    }

    @Test
    @DisplayName("아주 긴 키도 컬럼 길이를 넘지 않는다")
    void 긴_키를_축약한다() {
        Map<String, Object> values = Map.of("a", "X".repeat(1000));

        String key = builder.build(values, List.of("a"));

        assertThat(key.length()).isLessThanOrEqualTo(500);
    }
}
