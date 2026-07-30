package kr.suhsaechan.palim.collector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.suhsaechan.palim.channel.Channel;
import kr.suhsaechan.palim.channel.ChannelCredentialService;
import kr.suhsaechan.palim.channel.adapter.ChannelOrder;
import kr.suhsaechan.palim.channel.adapter.ChannelOrderCollector;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.order.OrderErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 채널 1개 수집 실행.
 *
 * <p><b>트랜잭션을 열지 않는다.</b> 채널 API 호출을 포함하므로, 트랜잭션 안에서 실행하면 외부
 * 응답을 기다리는 동안 커넥션과 락을 점유한다. 상태 기록은 {@link CollectStateService} 에,
 * 주문 처리는 {@link OrderIngestionService} 에 위임하고 각자 트랜잭션을 열게 한다.
 */
@Slf4j
@Component
public class ChannelCollectRunner {

    private final ChannelCredentialService channelCredentialService;
    private final OrderIngestionService orderIngestionService;
    private final CollectStateService collectStateService;
    private final CollectProperties collectProperties;
    private final Map<ChannelCode, ChannelOrderCollector> collectors;

    public ChannelCollectRunner(ChannelCredentialService channelCredentialService,
                                OrderIngestionService orderIngestionService,
                                CollectStateService collectStateService,
                                CollectProperties collectProperties,
                                List<ChannelOrderCollector> availableCollectors) {
        this.channelCredentialService = channelCredentialService;
        this.orderIngestionService = orderIngestionService;
        this.collectStateService = collectStateService;
        this.collectProperties = collectProperties;
        this.collectors = availableCollectors.stream().collect(Collectors.toUnmodifiableMap(
                ChannelOrderCollector::channelCode, Function.identity()));

        log.info("수집 어댑터 {}개 등록 — {}", collectors.size(), collectors.keySet());
    }

    public CollectSummary run(Channel channel, Instant now) {
        ChannelCode channelCode = channel.getCode();

        ChannelOrderCollector collector = collectors.get(channelCode);
        if (collector == null) {
            return CollectSummary.skipped(channelCode, "어댑터가 아직 구현되지 않았습니다");
        }

        Instant from = resolveFrom(channel, now);
        List<ChannelOrder> orders;
        try {
            Map<String, String> credentials = channelCredentialService.getAll(channelCode);
            orders = collector.collect(from, now, credentials);
        } catch (RuntimeException exception) {
            // 채널 호출 실패. 커서를 전진시키지 않으므로 다음 주기에 같은 구간을 다시 조회한다.
            String reason = describe(exception);
            log.error("채널 수집 실패 — {} 구간 [{} ~ {}]: {}", channelCode, from, now, reason, exception);
            collectStateService.recordFailure(channelCode, now, reason,
                    collectProperties.failureThreshold());
            return CollectSummary.failed(channelCode, reason);
        }

        List<IngestResult> results = new ArrayList<>();
        int failedOrderCount = 0;

        for (ChannelOrder order : orders) {
            try {
                results.add(orderIngestionService.ingest(order, now));
            } catch (BusinessException exception) {
                failedOrderCount++;
                logIngestFailure(channelCode, order, exception);
            } catch (RuntimeException exception) {
                failedOrderCount++;
                log.error("주문 처리 실패 — {} {}", channelCode, order.channelOrderNo(), exception);
            }
        }

        CollectSummary summary = CollectSummary.of(channelCode, orders.size(), results, failedOrderCount);

        if (summary.advancedCursor()) {
            collectStateService.recordSuccess(channelCode, now, now);
        } else {
            collectStateService.recordPartialFailure(channelCode, now, failedOrderCount);
        }
        return summary;
    }

    /**
     * 조회 시작 시각.
     *
     * <p>커서에서 겹침 여유만큼 앞으로 당긴다. 채널 API 는 주문 시각이 지연 반영되는 경우가
     * 있어 구간을 정확히 이어붙이면 경계에서 주문이 누락되는데, <b>중복은 유니크 제약이 막지만
     * 누락은 아무도 감지하지 못한다</b>(설계서 5.4).
     */
    private Instant resolveFrom(Channel channel, Instant now) {
        Instant collectedUntil = channel.getCollectedUntil();
        if (collectedUntil == null) {
            return now.minus(collectProperties.initialLookback());
        }
        return collectedUntil.minus(collectProperties.overlap());
    }

    /**
     * 주문 처리 실패 로깅.
     *
     * <p>중복 수집은 오류가 아니다. 다만 이 지점까지 예외가 올라온 것은 <b>1차 필터를 통과한 뒤
     * 삽입 시점에 경합이 발생</b>했다는 뜻이므로, 해당 주문의 트랜잭션은 롤백됐다. 커서를
     * 전진시키지 않으니 다음 주기에 재시도되고, 그때는 이미 저장되어 있어 1차 필터가 걸러낸다 —
     * 무한 반복되지 않는다.
     */
    private void logIngestFailure(ChannelCode channelCode, ChannelOrder order,
                                  BusinessException exception) {
        if (exception.is(OrderErrorCode.ORDER_LINE_DUPLICATE)) {
            log.debug("중복 수집 경합 — {} {}. 다음 주기에 정상 처리됩니다.",
                    channelCode, order.channelOrderNo());
            return;
        }
        log.error("주문 처리 실패 — {} {} ({})",
                channelCode, order.channelOrderNo(), exception.getErrorCode().name(), exception);
    }

    private static String describe(RuntimeException exception) {
        String message = exception.getMessage();
        return message != null && !message.isBlank()
                ? message
                : exception.getClass().getSimpleName();
    }
}
