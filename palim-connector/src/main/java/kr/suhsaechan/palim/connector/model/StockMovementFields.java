package kr.suhsaechan.palim.connector.model;

import static kr.suhsaechan.palim.connector.model.FieldDefinition.optional;
import static kr.suhsaechan.palim.connector.model.FieldDefinition.required;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 입출고 이력 — 사건.
 *
 * <p>{@code document_no} 가 자연키의 일부다. 전표 번호가 없으면 같은 품목의 같은 시각 이동을
 * 구분할 방법이 없어 재실행이 중복을 만든다.
 */
@Component
public class StockMovementFields implements StandardModelFields {

    @Override
    public String modelCode() {
        return "std_stock_movement";
    }

    @Override
    public List<FieldDefinition> fields() {
        return List.of(
                required("item_ref", "품목", FieldDataType.STRING),
                required("occurred_at", "발생 시각", FieldDataType.TIMESTAMP),
                required("movement_type", "구분", FieldDataType.STRING),
                optional("reason_code", "사유 코드", FieldDataType.STRING),

                required("quantity", "수량", FieldDataType.DECIMAL),
                optional("unit", "단위", FieldDataType.STRING),
                optional("base_quantity", "기준 단위 수량", FieldDataType.DECIMAL),
                optional("base_unit", "기준 단위", FieldDataType.STRING),

                optional("from_warehouse", "출발 창고", FieldDataType.STRING),
                optional("to_warehouse", "도착 창고", FieldDataType.STRING),
                optional("from_location", "출발 로케이션", FieldDataType.STRING),
                optional("to_location", "도착 로케이션", FieldDataType.STRING),

                optional("lot_code", "로트", FieldDataType.STRING),
                optional("expiry_date", "유통기한", FieldDataType.DATE),

                optional("document_no", "전표번호", FieldDataType.STRING),
                optional("document_name", "전표명", FieldDataType.STRING),
                optional("reference_no", "참조번호", FieldDataType.STRING),
                optional("operator", "작업자", FieldDataType.STRING));
    }
}
