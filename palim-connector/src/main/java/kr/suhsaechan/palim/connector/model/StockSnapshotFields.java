package kr.suhsaechan.palim.connector.model;

import static kr.suhsaechan.palim.connector.model.FieldDefinition.optional;
import static kr.suhsaechan.palim.connector.model.FieldDefinition.required;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 재고 스냅샷 — 특정 시점·위치의 상태.
 *
 * <p>{@code base_at} 이 필수인 이유는 두 원천을 다른 시각에 뽑으면 그 사이 출고분만큼 무조건
 * 차이가 나기 때문이다. 시점을 모르는 재고는 대사에 쓸 수 없다.
 */
@Component
public class StockSnapshotFields implements StandardModelFields {

    @Override
    public String modelCode() {
        return "std_stock_snapshot";
    }

    @Override
    public List<FieldDefinition> fields() {
        return List.of(
                required("item_ref", "품목", FieldDataType.STRING),
                required("source", "출처", FieldDataType.STRING),
                required("base_at", "기준 시각", FieldDataType.TIMESTAMP),
                optional("collected_at", "수집 시각", FieldDataType.TIMESTAMP),

                optional("warehouse_code", "창고 코드", FieldDataType.STRING),
                optional("warehouse_name", "창고명", FieldDataType.STRING),
                optional("location_code", "로케이션", FieldDataType.STRING),
                optional("zone_code", "구역", FieldDataType.STRING),

                optional("lot_code", "로트", FieldDataType.STRING),
                optional("expiry_date", "유통기한", FieldDataType.DATE),
                optional("manufacture_date", "제조일", FieldDataType.DATE),
                optional("serial_no", "시리얼", FieldDataType.STRING),

                required("quantity", "수량", FieldDataType.DECIMAL),
                optional("unit", "단위", FieldDataType.STRING),
                // 환산값은 코드가 채운다. 원천에서 직접 매핑하는 경우는 드물다.
                optional("base_quantity", "기준 단위 수량", FieldDataType.DECIMAL),
                optional("base_unit", "기준 단위", FieldDataType.STRING),
                optional("available_quantity", "가용 수량", FieldDataType.DECIMAL),
                optional("reserved_quantity", "할당 수량", FieldDataType.DECIMAL),
                optional("defective_quantity", "불량 수량", FieldDataType.DECIMAL),
                optional("incoming_quantity", "입고 예정", FieldDataType.DECIMAL),
                optional("outgoing_quantity", "출고 예정", FieldDataType.DECIMAL),

                optional("unit_cost", "단가", FieldDataType.DECIMAL),
                optional("amount", "금액", FieldDataType.DECIMAL),
                optional("currency", "통화", FieldDataType.STRING),
                optional("quality_status", "상태", FieldDataType.STRING),

                optional("raw_item_name", "원본 품명", FieldDataType.STRING),
                optional("normalized_name", "정규화 품명", FieldDataType.STRING),
                optional("product_key", "제품 키", FieldDataType.STRING));
    }
}
