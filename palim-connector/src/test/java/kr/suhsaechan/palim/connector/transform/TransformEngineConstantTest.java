package kr.suhsaechan.palim.connector.transform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.connector.model.FieldDataType;
import kr.suhsaechan.palim.connector.source.SourceRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 원천에 <b>아예 없는 항목</b>을 사람이 적어 넣는다.
 *
 * <p>이카운트는 「단위」 칸을 주지 않는다. 그렇다고 단위 없이 담으면 나중에 BOX 와 EA 가 섞인
 * 원천이 붙었을 때 두 수량을 구분할 방법이 없고, 그때는 이미 쌓인 자료를 되돌릴 수 없다.
 *
 * <p>{@code DEFAULT_IF_EMPTY} 로는 안 된다. 그것은 <b>원천 값이 비었을 때</b> 대신 쓰는
 * 규칙이라 원천 칸이 존재해야 한다. 여기서 필요한 것은 칸 자체가 없는 경우다.
 */
class TransformEngineConstantTest {

    private final TransformEngine engine = new TransformEngine();

    @Test
    @DisplayName("원천 칸 없이 적어 넣은 값이 모든 행에 들어간다")
    void 고정값이_모든_행에_들어간다() {
        List<TargetFieldSpec> specs = List.of(
                TargetFieldSpec.of("item_ref", FieldDataType.STRING, true),
                TargetFieldSpec.of("unit", FieldDataType.STRING, false));
        List<FieldMapping> mappings = List.of(
                FieldMapping.of("PROD_CD", "item_ref"),
                new FieldMapping("", "unit",
                        TransformRule.of(TransformType.CONSTANT, Map.of("value", "EA"))));

        MappedRow row = engine.map(new SourceRow(1, Map.of("PROD_CD", "A0001")), mappings, specs);

        assertThat(row.values().get("unit"))
                .as("원천에 없는 값을 사람이 적었으면 그대로 들어가야 한다")
                .isEqualTo("EA");
        assertThat(row.values().get("item_ref")).isEqualTo("A0001");
    }

    /**
     * 연결하지 않은 원천 칸은 버리지 않고 보존한다. 고정값은 원천 칸을 하나도 쓰지 않으므로
     * 그 보존 대상을 가로채면 안 된다 — 가로채면 사용자가 넣지도 않은 이유로 자료가 사라진다.
     */
    @Test
    @DisplayName("고정값은 원천 칸을 소비하지 않는다")
    void 고정값은_원천_칸을_쓰지_않는다() {
        List<TargetFieldSpec> specs = List.of(
                TargetFieldSpec.of("item_ref", FieldDataType.STRING, true),
                TargetFieldSpec.of("unit", FieldDataType.STRING, false));
        List<FieldMapping> mappings = List.of(
                FieldMapping.of("PROD_CD", "item_ref"),
                new FieldMapping("", "unit",
                        TransformRule.of(TransformType.CONSTANT, Map.of("value", "EA"))));

        MappedRow row = engine.map(
                new SourceRow(1, Map.of("PROD_CD", "A0001", "REMARK", "비고")), mappings, specs);

        assertThat(row.attributes())
                .as("연결하지 않은 칸은 보존된다. 고정값이 그 자리를 차지하면 안 된다")
                .containsKey("REMARK");
    }

    /**
     * 필수 항목을 고정값으로 채우는 경우도 있다. 원천이 출처를 알려주지 않으므로 우리가 넣는다.
     * 이때 필수 검사를 통과해야 한다 — 값이 실제로 채워졌기 때문이다.
     */
    @Test
    @DisplayName("필수 항목도 고정값으로 채울 수 있다")
    void 필수_항목도_고정값으로_채운다() {
        List<TargetFieldSpec> specs = List.of(
                TargetFieldSpec.of("item_ref", FieldDataType.STRING, true),
                TargetFieldSpec.of("source", FieldDataType.STRING, true));
        List<FieldMapping> mappings = List.of(
                FieldMapping.of("PROD_CD", "item_ref"),
                new FieldMapping("", "source",
                        TransformRule.of(TransformType.CONSTANT, Map.of("value", "erp-stock"))));

        MappedRow row = engine.map(new SourceRow(1, Map.of("PROD_CD", "A0001")), mappings, specs);

        assertThat(row.values().get("source")).isEqualTo("erp-stock");
    }
}
