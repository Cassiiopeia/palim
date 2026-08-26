package kr.suhsaechan.palim.automation.influencer.rising;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import kr.suhsaechan.palim.automation.influencer.InfluencerFeature;
import kr.suhsaechan.palim.common.config.ConfigReader;
import kr.suhsaechan.palim.notification.NotificationType;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.notification.payload.RisingInfluencerPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 라이징 주간 알림.
 *
 * <p>화면을 열지 않아도 기회가 먼저 도달하게 한다. 라이징은 <b>유통기한이 있는 정보</b>라
 * 발견이 늦으면 값이 사라지기 때문이다 — 2주 지나면 이미 단가가 올라 있다.
 *
 * <p>신규 감지분만 보낸다. 레이더에 계속 올라 있는 채널을 매주 다시 알리면 알림이 배경 소음이
 * 되고, 그때부터는 정작 새로 뜬 채널도 읽히지 않는다.
 *
 * <p>발송은 {@link OutboxService} 를 통한다 — 직접 봇 API 를 호출하지 않는다(05-INTEGRATION).
 * 실패해도 Outbox 에 남아 재시도되고 알림 이력 화면에서 확인된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RisingWeeklyNotifier {

    /** 메시지에 담을 상위 채널 수. 더 많으면 읽히지 않는다 — 상세는 화면에 있다. */
    private static final int MESSAGE_CHANNEL_LIMIT = 5;

    private final RisingSignalService risingSignalService;
    private final OutboxService outboxService;
    private final ConfigReader config;
    private final InfluencerFeature influencerFeature;
    private final Clock clock;

    /** 월요일 아침 9시. 한 주 광고 계획을 세우는 시점에 도달하게 한다. */
    @Scheduled(cron = "0 0 9 * * MON", zone = "Asia/Seoul")
    @Transactional
    public void notifyWeekly() {
        // 마스터 스위치가 꺼져 있으면 아무것도 하지 않는다. 아래 세부 설정보다 «앞» 에 두는
        // 이유는, 세부만 켜 두고 기능 전체를 껐을 때 이 하나가 살아 도는 일을 막기 위해서다.
        if (!influencerFeature.isEnabled()) {
            log.debug("인플루언서 기능 꺼짐 — {} 건너뜀", "라이징 주간 알림");
            return;
        }

        if (!config.getBoolean(RisingConfigKeys.WEEKLY_NOTIFICATION_ENABLED)) {
            log.debug("라이징 주간 알림 — 사용 안 함");
            return;
        }

        Instant now = Instant.now(clock);
        Instant since = now.minus(Duration.ofDays(
                config.getInt(RisingConfigKeys.NOTIFICATION_LOOKBACK_DAYS)));

        List<RisingSignal> detected = risingSignalService.findDetectedAfter(since,
                config.getInt(RisingConfigKeys.RADAR_LIMIT));

        if (detected.isEmpty()) {
            log.info("라이징 주간 알림 — 신규 감지 없음, 발송하지 않는다");
            return;
        }

        List<RisingInfluencerPayload.RisingChannel> channels = detected.stream()
                .limit(MESSAGE_CHANNEL_LIMIT)
                .map(signal -> new RisingInfluencerPayload.RisingChannel(
                        signal.getChannel().getTitle(),
                        signal.getChannel().getSubscriberCount(),
                        signal.getMedianViews(),
                        signal.getTotal().doubleValue(),
                        signal.getArbitrageRatio().doubleValue(),
                        signal.daysSinceDetected(now)))
                .toList();

        outboxService.enqueue(NotificationType.RISING_INFLUENCER,
                new RisingInfluencerPayload(detected.size(), risingSignalService.activeCount(),
                        channels, since));

        log.info("라이징 주간 알림 등록 — 신규 {}명", detected.size());
    }
}
