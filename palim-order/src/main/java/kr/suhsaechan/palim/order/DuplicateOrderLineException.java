package kr.suhsaechan.palim.order;

import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.common.exception.PalimException;
import lombok.Getter;

/**
 * 이미 수집된 주문 항목을 다시 저장하려 할 때 발생한다.
 *
 * <p><b>오류가 아니라 정상 흐름의 일부다.</b> 수집 커서는 구간을 겹쳐서 조회하므로(설계서 5.4)
 * 같은 주문이 반복 수집되는 것이 정상이며, 이 예외는 "이미 처리했으니 재고를 차감하지 말라"는
 * 신호다.
 *
 * <p>이 예외가 발생하면 데이터베이스 트랜잭션은 rollback-only 상태가 된다. 따라서 수집 조율은
 * <b>주문 1건 단위로 트랜잭션을 열어야 한다.</b> 여러 주문을 한 트랜잭션에서 처리하면 중복
 * 하나 때문에 정상 주문까지 롤백된다.
 */
@Getter
public class DuplicateOrderLineException extends PalimException {

    private final ChannelCode channelCode;
    private final String channelOrderNo;
    private final String channelLineNo;

    public DuplicateOrderLineException(ChannelCode channelCode, String channelOrderNo,
                                       String channelLineNo, Throwable cause) {
        super("이미 수집된 주문 항목입니다. 채널=%s, 주문번호=%s, 항목번호=%s"
                .formatted(channelCode, channelOrderNo, channelLineNo), cause);
        this.channelCode = channelCode;
        this.channelOrderNo = channelOrderNo;
        this.channelLineNo = channelLineNo;
    }
}
