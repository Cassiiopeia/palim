package kr.suhsaechan.palim.web.mapping;

import java.util.UUID;

/**
 * 매핑 목록 표시용 (F-04).
 *
 * <p>{@code ProductMapping} 은 SKU 를 {@code UUID} 값으로만 들고 있어 화면에 그대로 쓰면 어떤
 * 상품에 붙었는지 알 수 없다. 조율 계층에서 SKU 를 조회해 코드·이름을 합쳐 넘긴다.
 */
public record MappingView(
        UUID id,
        String channelProductNo,
        String channelOptionNo,
        String channelProductName,
        UUID skuId,
        String skuCode,
        String skuName,
        boolean active
) {

    public boolean hasOption() {
        return channelOptionNo != null && !channelOptionNo.isBlank();
    }
}
