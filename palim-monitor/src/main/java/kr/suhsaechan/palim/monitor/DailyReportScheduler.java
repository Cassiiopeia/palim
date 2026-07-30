package kr.suhsaechan.palim.monitor;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import kr.suhsaechan.palim.notification.NotificationSetting;
import kr.suhsaechan.palim.notification.NotificationSettingService;
import kr.suhsaechan.palim.notification.NotificationType;
import kr.suhsaechan.palim.notification.OutboxService;
import kr.suhsaechan.palim.notification.payload.DailyReportPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설정 시각에 전일 리포트를 발송한다 (F-06).
 *
 * <h2>왜 cron 이 아니라 주기 확인인가</h2>
 *
 * <p>발송 시각은 웹 관리자에서 변경 가능한 <b>설정값</b>이다(기본 09:00). {@code @Scheduled(cron)}
 * 은 애플리케이션 기동 시 표현식이 고정되므로, 발주자가 시각을 바꿀 때마다 재기동해야 한다.
 * F-06 은 재시작 없는 반영을 요구한다.
 *
 * <p>그래서 짧은 주기로 깨어나 "설정 시각이 지났는가"를 확인한다.
 *
 * <h2>중복 발송 방지</h2>
 *
 * <p>주기 확인 방식은 시각이 지난 뒤 <b>매 주기마다 조건을 만족</b>한다. 09:00 이 지나면
 * 09:05, 09:10 에도 발송하게 되므로 억제가 필수다.
 *
 * <p>{@code dedupeKey} 에 대상 날짜를 넣어 하루 한 번만 나가게 한다. 억제 기간을 넉넉히
 * 잡는 이유는, 날짜가 키에 포함되어 있어 다음 날에는 다른 키가 되기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReportScheduler {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 억제 기간.
     *
     * <p>날짜가 dedupeKey 에 포함되므로 하루보다 길게 잡아도 다음 날 발송을 막지 않는다.
     * 넉넉히 두면 스케줄러가 자정 전후에 깨어나도 중복이 나가지 않는다.
     */
    private static final Duration SUPPRESSION_WINDOW = Duration.ofHours(36);

    private final NotificationSettingService notificationSettingService;
    private final DailyReportAssembler dailyReportAssembler;
    private final OutboxService outboxService;

    /**
     * 발송 조건을 확인하고 등록한다.
     *
     * @return 등록했으면 true
     */
    @Transactional
    @Scheduled(fixedDelayString = "${palim.monitor.daily-report-delay:PT5M}")
    public boolean sendIfDue() {
        NotificationSetting setting = notificationSettingService.get();
        if (!setting.isDailyReportEnabled()) {
            return false;
        }

        LocalTime now = LocalTime.now(BUSINESS_ZONE);
        if (now.isBefore(setting.getDailyReportTime())) {
            return false;
        }

        LocalDate targetDate = LocalDate.now(BUSINESS_ZONE).minusDays(1);
        DailyReportPayload payload = dailyReportAssembler.assemble(targetDate);

        boolean registered = outboxService.enqueueIfNotRecent(
                NotificationType.DAILY_REPORT,
                "DAILY_REPORT:" + targetDate,
                SUPPRESSION_WINDOW,
                payload).isPresent();

        if (registered) {
            log.info("일일 리포트 등록 — {} 주문 {}건 / {}원",
                    targetDate, payload.totalOrderCount(), payload.totalAmount());
        }
        return registered;
    }
}
