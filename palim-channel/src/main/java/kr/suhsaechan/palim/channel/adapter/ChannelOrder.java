package kr.suhsaechan.palim.channel.adapter;

import java.time.Instant;
import java.util.List;
import kr.suhsaechan.palim.common.ChannelCode;

/**
 * 채널 어댑터가 반환하는 주문 공통 형식.
 *
 * <p>이 record 가 어댑터와 조율 계층의 경계다. 어댑터는 주문·재고 도메인 엔티티를 알지 못하고
 * 이 형식만 반환하며, 엔티티로 변환하는 책임은 {@code palim-collector} 에 있다(설계서 3.5).
 * 그래서 신규 채널 추가가 기존 코드에 영향을 주지 않는다.
 *
 * @param channelCode    수집 채널
 * @param channelOrderNo 채널 주문번호
 * @param orderedAt      주문 시각. 채널이 KST/UTC 를 섞어 주므로 <b>어댑터가 Instant 로 정규화</b>한다
 * @param buyerName      구매자명. 채널이 제공하지 않으면 null
 * @param totalAmount    주문 총액(원)
 * @param lines          주문 항목. 최소 1개
 */
public record ChannelOrder(
        ChannelCode channelCode,
        String channelOrderNo,
        Instant orderedAt,
        String buyerName,
        long totalAmount,
        List<ChannelOrderLine> lines
) {

    public ChannelOrder {
        if (channelCode == null) {
            throw new IllegalArgumentException("채널 코드가 없습니다");
        }
        if (channelOrderNo == null || channelOrderNo.isBlank()) {
            throw new IllegalArgumentException("채널 주문번호가 없습니다");
        }
        if (orderedAt == null) {
            throw new IllegalArgumentException("주문 시각이 없습니다");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("주문 항목이 없습니다: " + channelOrderNo);
        }
        lines = List.copyOf(lines);
    }
}
