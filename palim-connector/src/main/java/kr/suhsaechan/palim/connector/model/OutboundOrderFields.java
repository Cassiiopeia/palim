package kr.suhsaechan.palim.connector.model;

import static kr.suhsaechan.palim.connector.model.FieldDefinition.optional;
import static kr.suhsaechan.palim.connector.model.FieldDefinition.required;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 출고 주문.
 *
 * <p><b>개인정보를 담는 유일한 표준 모델이다.</b> 수령자 정보 때문이며, 보존기간·마스킹·접근
 * 권한을 이 모델에만 걸 수 있도록 다른 모델과 분리했다. 섞었다면 재고 조회 화면까지 개인정보
 * 취급 대상이 됐을 것이다.
 */
@Component
public class OutboundOrderFields implements StandardModelFields {

    @Override
    public String modelCode() {
        return "std_outbound_order";
    }

    @Override
    public List<FieldDefinition> fields() {
        return List.of(
                required("order_no", "주문번호", FieldDataType.STRING),
                optional("order_line_no", "주문상세번호", FieldDataType.INTEGER),
                optional("ordered_at", "주문 시각", FieldDataType.TIMESTAMP),
                optional("channel", "판매처", FieldDataType.STRING),

                required("item_ref", "품목", FieldDataType.STRING),
                required("quantity", "수량", FieldDataType.DECIMAL),
                optional("unit_price", "단가", FieldDataType.DECIMAL),

                // 여기부터 개인정보다.
                optional("receiver_name", "수령자명", FieldDataType.STRING),
                optional("receiver_phone", "수령자 연락처", FieldDataType.STRING),
                optional("receiver_address", "수령자 주소", FieldDataType.STRING),
                optional("postal_code", "우편번호", FieldDataType.STRING),
                optional("delivery_memo", "배송 메모", FieldDataType.STRING),

                optional("carrier", "택배사", FieldDataType.STRING),
                optional("tracking_no", "송장번호", FieldDataType.STRING),
                optional("status", "상태", FieldDataType.STRING),
                optional("shipped_at", "출고 시각", FieldDataType.TIMESTAMP));
    }
}
