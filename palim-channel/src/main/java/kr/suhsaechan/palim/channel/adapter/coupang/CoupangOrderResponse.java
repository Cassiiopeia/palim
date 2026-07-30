package kr.suhsaechan.palim.channel.adapter.coupang;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 쿠팡 주문 조회 응답.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 를 붙인 이유는, 쿠팡이 필드를
 * <b>추가</b>하는 것만으로 파싱이 깨지면 안 되기 때문이다. 반대로 필드가 <b>사라지거나 의미가
 * 바뀌는</b> 변경은 보관된 응답 샘플 기반 회귀 테스트가 잡는다(05-INTEGRATION).
 *
 * <p>필드명은 쿠팡 규격을 그대로 따른다. 도메인 이름으로 바꾸지 않는다 — 응답 구조를 그대로
 * 유지해야 실제 응답과 대조하기 쉽다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoupangOrderResponse(
        int code,
        String message,
        List<OrderSheet> data,
        String nextToken
) {

    public CoupangOrderResponse {
        data = data != null ? List.copyOf(data) : List.of();
    }

    /** 다음 페이지가 있는지. 빈 문자열도 없음으로 취급한다. */
    public boolean hasNextPage() {
        return nextToken != null && !nextToken.isBlank();
    }

    /** 주문 1건. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderSheet(
            long orderId,
            String orderedAt,
            String ordererName,
            long totalPaidAmount,
            String status,
            List<OrderItem> orderItems
    ) {

        public OrderSheet {
            orderItems = orderItems != null ? List.copyOf(orderItems) : List.of();
        }
    }

    /**
     * 주문 항목.
     *
     * <p>{@code vendorItemId} 가 옵션 단위 식별자다. 쿠팡은 같은 상품의 색상·사이즈를 서로 다른
     * {@code vendorItemId} 로 구분하므로, 이 값이 재고 차감 대상을 결정한다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderItem(
            long vendorItemPackageId,
            long productId,
            long vendorItemId,
            String vendorItemName,
            int shippingCount,
            long salesPrice,
            long orderPrice
    ) {
    }
}
