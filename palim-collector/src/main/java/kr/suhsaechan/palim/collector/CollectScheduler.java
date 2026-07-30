package kr.suhsaechan.palim.collector;

import java.time.Instant;
import java.util.List;
import kr.suhsaechan.palim.channel.Channel;
import kr.suhsaechan.palim.channel.ChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 수집 스케줄러.
 *
 * <p>짧은 주기로 돌면서 <b>수집 시각이 도래한 채널만</b> 처리한다. 채널별 주기는
 * {@code Channel.collectIntervalSeconds} 가 정하고 판정은 엔티티가 한다 — 스케줄러가 채널별
 * 주기를 알 필요가 없다.
 *
 * <p>전 채널이 웹훅을 제공하지 않아 주기적 조회 방식으로 동작하며, 따라서 주문 발생과 알림
 * 사이에는 수집 주기만큼의 지연이 있다(기능 명세서 F-01).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollectScheduler {

    private final ChannelService channelService;
    private final ChannelCollectRunner channelCollectRunner;

    @Scheduled(fixedDelayString = "${palim.collect.scheduler-delay:60000}")
    public void collectDueChannels() {
        Instant now = Instant.now();
        List<Channel> dueChannels = channelService.findDue(now);

        if (dueChannels.isEmpty()) {
            return;
        }

        for (Channel channel : dueChannels) {
            try {
                CollectSummary summary = channelCollectRunner.run(channel, now);
                logSummary(summary);
            } catch (RuntimeException exception) {
                // 한 채널의 실패가 다른 채널 수집을 막지 않는다.
                log.error("채널 수집 중 예상하지 못한 오류 — {}", channel.getCode(), exception);
            }
        }
    }

    private void logSummary(CollectSummary summary) {
        switch (summary.outcome()) {
            case SUCCESS -> {
                if (summary.newLineCount() > 0 || summary.unmappedLineCount() > 0) {
                    log.info("수집 완료 — {} 주문 {}건 (신규 {}, 중복 {}, 미매핑 {}, 오버셀 {})",
                            summary.channelCode(), summary.orderCount(), summary.newLineCount(),
                            summary.duplicateLineCount(), summary.unmappedLineCount(),
                            summary.oversoldLineCount());
                }
            }
            case PARTIAL -> log.warn("수집 부분 실패 — {} 주문 {}건 중 {}건 실패",
                    summary.channelCode(), summary.orderCount(), summary.failedOrderCount());
            case FAILED -> log.error("수집 실패 — {}: {}",
                    summary.channelCode(), summary.errorMessage());
            case SKIPPED -> log.debug("수집 건너뜀 — {}: {}",
                    summary.channelCode(), summary.errorMessage());
        }
    }
}
