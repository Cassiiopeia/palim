package kr.suhsaechan.palim.connector.model;

import static kr.suhsaechan.palim.connector.model.FieldDefinition.optional;
import static kr.suhsaechan.palim.connector.model.FieldDefinition.required;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 품목 마스터 — 변하지 않는 것.
 *
 * <p>{@code supplier_item_code}/{@code supplier_item_name} 을 따로 두는 이유는 같은 물건을
 * 공급처가 다르게 부르는 일이 업종을 가리지 않고 발생하기 때문이다.
 */
@Component
public class ItemFields implements StandardModelFields {

    @Override
    public String modelCode() {
        return "std_item";
    }

    @Override
    public List<FieldDefinition> fields() {
        return List.of(
                required("item_code", "품목 코드", FieldDataType.STRING),
                required("item_name", "품목명", FieldDataType.STRING),
                optional("barcode", "바코드", FieldDataType.STRING),
                optional("external_id", "외부 식별자", FieldDataType.STRING),
                optional("spec", "규격", FieldDataType.STRING),
                optional("option_name", "옵션", FieldDataType.STRING),

                optional("category_code", "분류 코드", FieldDataType.STRING),
                optional("category_name", "분류명", FieldDataType.STRING),
                optional("brand", "브랜드", FieldDataType.STRING),
                optional("manufacturer", "제조사", FieldDataType.STRING),
                optional("origin_country", "원산지", FieldDataType.STRING),

                optional("supplier_code", "공급처 코드", FieldDataType.STRING),
                optional("supplier_name", "공급처명", FieldDataType.STRING),
                optional("supplier_item_code", "공급처 품목코드", FieldDataType.STRING),
                optional("supplier_item_name", "공급처 품목명", FieldDataType.STRING),

                optional("base_unit", "기준 단위", FieldDataType.STRING),
                optional("pack_size", "입수", FieldDataType.INTEGER),
                optional("weight", "중량", FieldDataType.DECIMAL),
                optional("volume", "부피", FieldDataType.DECIMAL),

                optional("standard_cost", "표준 원가", FieldDataType.DECIMAL),
                optional("sale_price", "판매가", FieldDataType.DECIMAL),
                optional("currency", "통화", FieldDataType.STRING),

                optional("is_active", "사용 여부", FieldDataType.BOOLEAN),
                optional("discontinued_at", "단종 시각", FieldDataType.TIMESTAMP));
    }
}
