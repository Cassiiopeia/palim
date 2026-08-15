package kr.suhsaechan.palim.connector.transform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.model.FieldDataType;
import kr.suhsaechan.palim.connector.source.SourceRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 변환 규칙 엔진.
 *
 * <p>실패는 행 단위여야 한다. 여기서 던진 예외를 오케스트레이터가 잡아 그 행만 걸러내고
 * 나머지는 계속 적재한다 — 한 행 때문에 1000행을 버리면 아무도 쓰지 않는다.
 */
class TransformEngineTest {

    private final TransformEngine engine = new TransformEngine();

    @Test
    @DisplayName("매핑된 필드를 목표 키로 옮긴다")
    void 필드를_옮긴다() {
        MappedRow mapped = engine.map(
                row(Map.of("품목코드", "A-001", "재고수량", "120")),
                List.of(FieldMapping.of("품목코드", "item_ref"),
                        FieldMapping.of("재고수량", "quantity")),
                List.of(spec("item_ref", FieldDataType.STRING, true),
                        spec("quantity", FieldDataType.DECIMAL, true)), java.util.Map.of());

        assertThat(mapped.values().get("item_ref")).isEqualTo("A-001");
        assertThat((BigDecimal) mapped.values().get("quantity")).isEqualByComparingTo("120");
    }

    @Test
    @DisplayName("매핑되지 않은 원천 컬럼은 attributes 로 보존한다")
    void 미매핑_컬럼을_보존한다() {
        MappedRow mapped = engine.map(
                row(Map.of("품목코드", "A-001", "비고", "메모")),
                List.of(FieldMapping.of("품목코드", "item_ref")),
                List.of(spec("item_ref", FieldDataType.STRING, true)), java.util.Map.of());

        assertThat(mapped.attributes())
                .as("버리면 과거 시점 데이터를 다시 받을 수 없다")
                .containsEntry("비고", "메모");
    }

    /**
     * 필수 검사는 <b>매핑이 아니라 담기 직전</b>에 한다.
     *
     * <p>둘 사이에 사장님이 쓴 후처리 스크립트가 돈다. 매핑에서 검사를 끝내면 스크립트가 그
     * 뒤에 필수 칸을 지워도 통과한 채로 담겨, <b>빈 품목코드가 조용히 들어간다.</b>
     */
    @Test
    @DisplayName("필수 필드가 비면 담기 직전 검사에서 걸린다")
    void 필수_필드가_비면_실패() {
        List<TargetFieldSpec> specs = List.of(spec("item_ref", FieldDataType.STRING, true));
        List<FieldMapping> mappings = List.of(FieldMapping.of("품목코드", "item_ref"));

        MappedRow mapped = engine.map(row(Map.of("품목코드", "")), mappings, specs,
                java.util.Map.of());

        assertThatThrownBy(() -> engine.verifyRequired(mapped, specs, mappings, mapped.values()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REQUIRED_FIELD_MISSING);
    }

    /**
     * 스크립트가 필수 칸을 지운 경우.
     *
     * <p>모든 칸을 열어 두기로 했으므로 이런 일이 실제로 생긴다. 막지 않고 <b>담기 직전에
     * 잡아</b> 그 줄만 떨어뜨린다 — 진짜 표는 멀쩡하고, 왜 떨어졌는지는 기록에 남는다.
     */
    @Test
    @DisplayName("스크립트가 필수 칸을 지워도 담기 직전에 걸린다")
    void 스크립트가_지워도_걸린다() {
        List<TargetFieldSpec> specs = List.of(spec("item_ref", FieldDataType.STRING, true));
        List<FieldMapping> mappings = List.of(FieldMapping.of("품목코드", "item_ref"));

        MappedRow mapped = engine.map(row(Map.of("품목코드", "A-001")), mappings, specs,
                java.util.Map.of());
        // 스크립트가 돌고 난 뒤의 모습 — 품목코드가 지워졌다
        MappedRow afterScript = new MappedRow(mapped.rowNumber(),
                new java.util.LinkedHashMap<>(Map.of("item_ref", "")), mapped.attributes());

        assertThatThrownBy(() ->
                engine.verifyRequired(afterScript, specs, mappings, afterScript.values()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REQUIRED_FIELD_MISSING);
    }

    @Test
    @DisplayName("선택 필드가 비어도 통과한다")
    void 선택_필드는_비어도_된다() {
        MappedRow mapped = engine.map(
                row(Map.of("코드", "A-001", "메모", "")),
                List.of(FieldMapping.of("코드", "item_ref"), FieldMapping.of("메모", "note")),
                List.of(spec("item_ref", FieldDataType.STRING, true),
                        spec("note", FieldDataType.STRING, false)), java.util.Map.of());

        assertThat(mapped.values().get("item_ref")).isEqualTo("A-001");
    }

    @Test
    @DisplayName("숫자로 읽을 수 없는 값은 실패시킨다")
    void 타입이_안_맞으면_실패() {
        assertThatThrownBy(() -> engine.map(
                row(Map.of("재고수량", "없음")),
                List.of(FieldMapping.of("재고수량", "quantity")),
                List.of(spec("quantity", FieldDataType.DECIMAL, true)), java.util.Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FIELD_TYPE_MISMATCH);
    }

    @Nested
    @DisplayName("날짜·시각")
    class DateTimeParsing {

        @Test
        @DisplayName("엑셀에서 온 날짜는 시간이 붙어 있어도 받는다")
        void 엑셀_날짜를_받는다() {
            // openpyxl 이 date 셀을 datetime 으로 읽어 이 형태로 온다.
            MappedRow mapped = engine.map(
                    row(Map.of("유통기한", "2027-01-01T00:00:00")),
                    List.of(FieldMapping.of("유통기한", "expiry_date")),
                    List.of(spec("expiry_date", FieldDataType.DATE, false)), java.util.Map.of());

            assertThat(mapped.values().get("expiry_date"))
                    .as("날짜만 받는다고 가정하면 유통기한 필드가 통째로 실패한다")
                    .isEqualTo(LocalDate.of(2027, 1, 1));
        }

        @Test
        @DisplayName("날짜만 온 값도 받는다")
        void 순수_날짜도_받는다() {
            MappedRow mapped = engine.map(
                    row(Map.of("유통기한", "2027-01-01")),
                    List.of(FieldMapping.of("유통기한", "expiry_date")),
                    List.of(spec("expiry_date", FieldDataType.DATE, false)), java.util.Map.of());

            assertThat(mapped.values().get("expiry_date")).isEqualTo(LocalDate.of(2027, 1, 1));
        }

        @Test
        @DisplayName("타임존이 붙은 시각은 그대로 읽는다")
        void 타임존이_있으면_그대로() {
            MappedRow mapped = engine.map(
                    row(Map.of("기준시각", "2026-08-12T00:00:00Z")),
                    List.of(FieldMapping.of("기준시각", "base_at")),
                    List.of(spec("base_at", FieldDataType.TIMESTAMP, true)), java.util.Map.of());

            assertThat(mapped.values().get("base_at"))
                    .isEqualTo(Instant.parse("2026-08-12T00:00:00Z"));
        }

        @Test
        @DisplayName("타임존 없는 시각은 규칙의 존으로 해석한다")
        void 존을_지정한다() {
            FieldMapping mapping = new FieldMapping("기준시각", "base_at",
                    TransformRule.of(TransformType.NONE, Map.of("zone", "Asia/Seoul")));

            MappedRow mapped = engine.map(
                    row(Map.of("기준시각", "2026-08-12T09:00:00")),
                    List.of(mapping),
                    List.of(spec("base_at", FieldDataType.TIMESTAMP, true)), java.util.Map.of());

            assertThat(mapped.values().get("base_at"))
                    .as("KST 09:00 은 UTC 00:00 이다")
                    .isEqualTo(Instant.parse("2026-08-12T00:00:00Z"));
        }

        @Test
        @DisplayName("시각 필드에 날짜만 와도 그날 0시로 읽는다")
        void 날짜만_와도_받는다() {
            MappedRow mapped = engine.map(
                    row(Map.of("기준일", "2026-08-12")),
                    List.of(FieldMapping.of("기준일", "base_at")),
                    List.of(spec("base_at", FieldDataType.TIMESTAMP, true)), java.util.Map.of());

            assertThat(mapped.values().get("base_at"))
                    .isEqualTo(Instant.parse("2026-08-12T00:00:00Z"));
        }

        @Test
        @DisplayName("원천 날짜 형식은 패턴으로 정규화한다")
        void 패턴으로_정규화한다() {
            FieldMapping mapping = new FieldMapping("유통기한", "expiry_date",
                    TransformRule.of(TransformType.DATE_FORMAT, Map.of("pattern", "yyyyMMdd")));

            MappedRow mapped = engine.map(
                    row(Map.of("유통기한", "20270101")),
                    List.of(mapping),
                    List.of(spec("expiry_date", FieldDataType.DATE, false)), java.util.Map.of());

            assertThat(mapped.values().get("expiry_date")).isEqualTo(LocalDate.of(2027, 1, 1));
        }
    }

    @Nested
    @DisplayName("변환 규칙")
    class Rules {

        @Test
        @DisplayName("천 단위 쉼표와 단위 표기를 걷어낸다")
        void 숫자만_남긴다() {
            FieldMapping mapping = new FieldMapping("수량", "quantity",
                    TransformRule.of(TransformType.NUMBER_STRIP, Map.of()));

            MappedRow mapped = engine.map(
                    row(Map.of("수량", "1,200 개")),
                    List.of(mapping),
                    List.of(spec("quantity", FieldDataType.DECIMAL, true)), java.util.Map.of());

            assertThat((BigDecimal) mapped.values().get("quantity")).isEqualByComparingTo("1200");
        }

        @Test
        @DisplayName("코드 치환표로 값을 바꾼다")
        void 코드를_치환한다() {
            FieldMapping mapping = new FieldMapping("구분", "movement_type",
                    TransformRule.of(TransformType.CODE_REPLACE,
                            Map.of("기본출고", "OUTBOUND", "기본입고", "INBOUND")));

            MappedRow mapped = engine.map(
                    row(Map.of("구분", "기본출고")),
                    List.of(mapping),
                    List.of(spec("movement_type", FieldDataType.STRING, true)), java.util.Map.of());

            assertThat(mapped.values().get("movement_type")).isEqualTo("OUTBOUND");
        }

        @Test
        @DisplayName("치환표에 없는 값은 그대로 둔다")
        void 치환표에_없으면_원본() {
            FieldMapping mapping = new FieldMapping("구분", "movement_type",
                    TransformRule.of(TransformType.CODE_REPLACE, Map.of("기본출고", "OUTBOUND")));

            MappedRow mapped = engine.map(
                    row(Map.of("구분", "기타조정")),
                    List.of(mapping),
                    List.of(spec("movement_type", FieldDataType.STRING, true)), java.util.Map.of());

            assertThat(mapped.values().get("movement_type")).isEqualTo("기타조정");
        }

        @Test
        @DisplayName("빈 값을 기본값으로 채운다")
        void 기본값을_채운다() {
            FieldMapping mapping = new FieldMapping("창고", "warehouse_code",
                    TransformRule.of(TransformType.DEFAULT_IF_EMPTY, Map.of("value", "MAIN")));

            MappedRow mapped = engine.map(
                    row(Map.of("창고", "")),
                    List.of(mapping),
                    List.of(spec("warehouse_code", FieldDataType.STRING, false)), java.util.Map.of());

            assertThat(mapped.values().get("warehouse_code")).isEqualTo("MAIN");
        }

        @Test
        @DisplayName("불린은 한국어 표기도 받는다")
        void 불린_표기() {
            MappedRow mapped = engine.map(
                    row(Map.of("사용", "예")),
                    List.of(FieldMapping.of("사용", "is_active")),
                    List.of(spec("is_active", FieldDataType.BOOLEAN, false)), java.util.Map.of());

            assertThat(mapped.values().get("is_active")).isEqualTo(true);
        }
    }

    private SourceRow row(Map<String, Object> values) {
        return new SourceRow(1, values);
    }

    private TargetFieldSpec spec(String key, FieldDataType type, boolean required) {
        return TargetFieldSpec.of(key, type, required);
    }

    /**
     * 사람이 고르지 않고 <b>시스템이 채우는 값</b>이 실제로 들어가는가.
     *
     * <p>출처·기준 시각은 상대가 보내주는 것이 아니라 우리가 이미 아는 값이다. 이것을 화면에서
     * 물어보면 사장님은 넣을 칸이 없어 매핑을 끝낼 수 없고, 그렇다고 채우는 코드가 없으면
     * <b>필수 검사에 걸려 전 행이 실패한다.</b> 실제로 그 상태였다.
     */
    @Test
    @DisplayName("시스템이 채우는 값이 그대로 들어간다")
    void 시스템값이_들어간다() {
        Instant baseAt = Instant.parse("2026-08-14T00:00:00Z");

        MappedRow mapped = engine.map(
                row(Map.of("재고수량", "10")),
                List.of(FieldMapping.of("재고수량", "quantity")),
                List.of(spec("quantity", FieldDataType.DECIMAL, true),
                        spec("source", FieldDataType.STRING, true),
                        spec("base_at", FieldDataType.TIMESTAMP, true)),
                Map.of("source", "ecount-stock", "base_at", baseAt));

        assertThat(mapped.values())
                .as("채우지 않으면 필수 검사에 걸려 전 행이 실패한다")
                .containsEntry("source", "ecount-stock")
                .containsEntry("base_at", baseAt);
    }

    /**
     * 원천이 자기 기준 시각을 실어 보내는 경우가 있다. 그때는 <b>사람이 정한 매핑이 이긴다</b> —
     * 상대가 직접 알려 준 값이 우리가 추측한 것보다 정확하기 때문이다.
     */
    @Test
    @DisplayName("같은 칸을 매핑하면 사람이 정한 쪽이 이긴다")
    void 매핑이_시스템값을_덮는다() {
        MappedRow mapped = engine.map(
                row(Map.of("보낸이름", "상대가 보낸 이름")),
                List.of(FieldMapping.of("보낸이름", "source")),
                List.of(spec("source", FieldDataType.STRING, true)),
                Map.of("source", "우리가 정한 이름"));

        assertThat(mapped.values()).containsEntry("source", "상대가 보낸 이름");
    }
}
