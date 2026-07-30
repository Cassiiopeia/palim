package kr.suhsaechan.palim.web.mapping;

import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.ChannelCode;

/**
 * 미매핑 주문 항목 표시용 (F-04).
 *
 * <p>화면에서 바로 SKU 를 연결할 수 있도록 매핑에 필요한 값을 전부 담는다. 발주자가 채널
 * 상품코드를 따로 찾아 입력하게 만들면 오타가 나고, <b>오타 난 매핑은 영원히 매칭되지 않는다.</b>
 */
public record UnmappedLineView(
        UUID orderLineId,
        ChannelCode channelCode,
        String channelName,
        String channelOrderNo,
        String channelProductNo,
        String channelOptionNo,
        String channelProductName,
        int quantity,
        Instant collectedAt
) {

    public boolean hasOption() {
        return channelOptionNo != null && !channelOptionNo.isBlank();
    }
}
