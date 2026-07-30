package kr.suhsaechan.palim.collector;

import java.time.Instant;
import kr.suhsaechan.palim.channel.Channel;
import kr.suhsaechan.palim.channel.ChannelService;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.notification.NotificationType;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.notification.payload.CollectFailurePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집 상태 기록.
 *
 * <p>도메인 서비스의 변경 메서드가 {@code MANDATORY} 이므로 트랜잭션을 여는 계층이 필요하다.
 * {@link ChannelCollectRunner} 는 외부 API 호출을 포함하므로 트랜잭션을 열지 않고, 상태 기록만
 * 이 서비스에 위임한다. <b>락을 잡은 채 외부 API 를 기다리는 상황을 만들지 않기 위함이다.</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectStateService {

    private final ChannelService channelService;
    private final OutboxService outboxService;

    /** 수집 성공. 커서를 전진시키고 실패 카운터를 초기화한다. */
    @Transactional
    public void recordSuccess(ChannelCode channelCode, Instant collectedUntil, Instant collectedAt) {
        channelService.recordCollectSuccess(channelCode, collectedUntil, collectedAt);
    }

    /**
     * 일부 주문 처리 실패.
     *
     * <p><b>커서를 전진시키지 않는다.</b> 전진시키면 실패한 주문이 영구 유실되기 때문이다.
     * 다음 주기에 같은 구간을 다시 조회하면 성공했던 주문은 유니크 제약으로 걸러지고 실패한
     * 것만 재시도된다 — 겹침 수집과 유니크 제약이 이 재시도를 안전하게 만든다.
     *
     * <p>연속 실패 카운터도 올리지 않는다. 채널은 정상 응답했고 일부 주문만 문제였으므로
     * 채널을 비활성화할 이유가 없다.
     */
    @Transactional
    public void recordPartialFailure(ChannelCode channelCode, Instant attemptedAt, int failedOrderCount) {
        log.warn("수집 부분 실패 — {} 주문 {}건 처리 실패. 커서를 전진시키지 않아 다음 주기에 재시도됩니다.",
                channelCode, failedOrderCount);
    }

    /**
     * 채널 호출 자체 실패.
     *
     * <p>연속 실패가 임계치에 도달하면 채널을 자동 비활성화한다. 쿠팡은 인증 실패나 호출 제한
     * 초과가 지속되면 <b>영구 차단</b>되므로, 실패를 반복하는 것보다 멈추는 편이 안전하다.
     *
     * @return 자동 비활성화되었는지 여부
     */
    @Transactional
    public boolean recordFailure(ChannelCode channelCode, Instant attemptedAt,
                                 String errorMessage, int failureThreshold) {
        channelService.recordCollectFailure(channelCode, attemptedAt, errorMessage);

        Channel channel = channelService.getByCode(channelCode);
        boolean autoDisabled = channel.hasReachedFailureThreshold(failureThreshold);
        if (autoDisabled) {
            channelService.disable(channelCode);
            log.error("연속 실패 {}회로 {} 채널 수집을 중단했습니다. 인증정보와 IP 등록을 확인하세요.",
                    channel.getConsecutiveFailureCount(), channelCode);
        }

        outboxService.enqueue(NotificationType.COLLECT_FAILURE, new CollectFailurePayload(
                channelCode.displayName(),
                channel.getConsecutiveFailureCount(),
                autoDisabled,
                errorMessage,
                attemptedAt));

        return autoDisabled;
    }
}
