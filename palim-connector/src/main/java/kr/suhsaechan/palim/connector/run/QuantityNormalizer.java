package kr.suhsaechan.palim.connector.run;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.suhsaechan.palim.connector.transform.MappedRow;
import kr.suhsaechan.palim.connector.unit.ConvertedQuantity;
import kr.suhsaechan.palim.connector.unit.UnitConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 수량 필드의 기준 단위 환산.
 *
 * <p>목표 모델에 {@code base_quantity} 가 <b>있을 때만</b> 동작한다. 커스텀 모델처럼 수량 개념이
 * 없는 모델에는 아무 일도 하지 않으므로, 연동 엔진이 특정 도메인에 묶이지 않는다.
 *
 * <p>변환 엔진과 분리한 이유는 환산이 <b>DB 조회를 동반</b>하기 때문이다. 변환 엔진은 순수
 * 함수로 두어야 단위 테스트가 컨테이너 없이 돌고 규칙을 읽기 쉽다.
 */
@Component
@RequiredArgsConstructor
public class QuantityNormalizer {

    private static final String QUANTITY = "quantity";
    private static final String UNIT = "unit";
    private static final String BASE_QUANTITY = "base_quantity";
    private static final String BASE_UNIT = "base_unit";
    private static final String ITEM_REF = "item_ref";

    private final UnitConverter converter;

    /**
     * @param targetHasBaseQuantity 목표 모델에 {@code base_quantity} 필드가 있는지
     * @param defaultUnit           커넥터의 기준 단위
     */
    public MappedRow normalize(UUID tenantId, MappedRow row, boolean targetHasBaseQuantity,
                               String defaultUnit) {
        if (!targetHasBaseQuantity) {
            return row;
        }
        Object quantity = row.values().get(QUANTITY);
        if (!(quantity instanceof BigDecimal amount)) {
            return row;
        }

        ConvertedQuantity converted = converter.convert(tenantId,
                Objects.toString(row.values().get(ITEM_REF), null),
                amount, Objects.toString(row.values().get(UNIT), null), defaultUnit);

        Map<String, Object> values = new LinkedHashMap<>(row.values());
        values.put(BASE_QUANTITY, converted.baseQuantity());
        values.put(BASE_UNIT, converted.baseUnit());

        return new MappedRow(row.rowNumber(), values, row.attributes());
    }
}
