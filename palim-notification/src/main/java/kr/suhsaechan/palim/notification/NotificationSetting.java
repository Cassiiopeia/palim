package kr.suhsaechan.palim.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalTime;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.common.error.BusinessException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림 설정 (F-02, F-05, F-06). 단일 행으로 관리한다.
 *
 * <p>웹에서 변경하면 재시작 없이 즉시 반영되어야 하므로 설정 파일이 아니라 테이블에 둔다.
 *
 * <p>{@code quietHours}·{@code dailyReportTime}은 {@link LocalTime}을 쓴다. 절대 시각이 아니라
 * <b>하루 중 시점</b>이므로 {@code Instant} 규칙(설계서 4.3)의 의도적 예외다.
 */
@Getter
@Entity
@Table(name = "notification_setting")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSetting extends BaseTimeEntity {

    private static final int DEFAULT_BATCH_INTERVAL_MINUTES = 30;
    private static final int DEFAULT_LOW_STOCK_REPEAT_HOURS = 24;
    private static final LocalTime DEFAULT_DAILY_REPORT_TIME = LocalTime.of(9, 0);

    @Id
    private UUID id;

    /** 텔레그램 수신 대상. 등록되지 않은 계정의 요청에는 응답하지 않는다(F-11). */
    @Column(length = 50)
    private String telegramChatId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderAlertMode orderAlertMode;

    @Column(nullable = false)
    private int batchIntervalMinutes;

    /** 야간 발송 보류 시작. null 이면 사용하지 않는다. */
    private LocalTime quietHoursStart;

    private LocalTime quietHoursEnd;

    @Column(nullable = false)
    private boolean dailyReportEnabled;

    @Column(nullable = false)
    private LocalTime dailyReportTime;

    /** 재고 부족 상태가 지속될 때 재알림 주기. */
    @Column(nullable = false)
    private int lowStockRepeatHours;

    @Version
    private Long version;

    /**
     * 파라미터를 받는 생성자만 둔다.
     *
     * <p>{@code @NoArgsConstructor(PROTECTED)}가 이미 파라미터 없는 생성자를 만들기 때문에,
     * 값을 채우는 생성자도 파라미터를 받아야 시그니처가 충돌하지 않는다.
     */
    private NotificationSetting(OrderAlertMode orderAlertMode, int batchIntervalMinutes,
                                boolean dailyReportEnabled, LocalTime dailyReportTime,
                                int lowStockRepeatHours) {
        this.id = UuidV7.generate();
        this.orderAlertMode = orderAlertMode;
        this.batchIntervalMinutes = batchIntervalMinutes;
        this.dailyReportEnabled = dailyReportEnabled;
        this.dailyReportTime = dailyReportTime;
        this.lowStockRepeatHours = lowStockRepeatHours;
    }

    /** 기능 명세서가 정의한 기본값으로 초기화한다. */
    public static NotificationSetting createDefault() {
        return new NotificationSetting(
                OrderAlertMode.IMMEDIATE,
                DEFAULT_BATCH_INTERVAL_MINUTES,
                true,
                DEFAULT_DAILY_REPORT_TIME,
                DEFAULT_LOW_STOCK_REPEAT_HOURS);
    }

    public void connectTelegram(String telegramChatId) {
        this.telegramChatId = telegramChatId;
    }

    public void changeOrderAlertMode(OrderAlertMode mode, int batchIntervalMinutes) {
        if (mode == OrderAlertMode.BATCHED && batchIntervalMinutes <= 0) {
            throw new BusinessException(NotificationErrorCode.INVALID_BATCH_INTERVAL, batchIntervalMinutes);
        }
        this.orderAlertMode = mode;
        this.batchIntervalMinutes = batchIntervalMinutes;
    }

    public void changeQuietHours(LocalTime start, LocalTime end) {
        if ((start == null) != (end == null)) {
            throw new BusinessException(NotificationErrorCode.INVALID_QUIET_HOURS);
        }
        this.quietHoursStart = start;
        this.quietHoursEnd = end;
    }

    public void changeDailyReport(boolean enabled, LocalTime time) {
        this.dailyReportEnabled = enabled;
        if (time != null) {
            this.dailyReportTime = time;
        }
    }

    public void changeLowStockRepeatHours(int hours) {
        if (hours <= 0) {
            throw new BusinessException(NotificationErrorCode.INVALID_REPEAT_HOURS, hours);
        }
        this.lowStockRepeatHours = hours;
    }

    public boolean isTelegramConnected() {
        return telegramChatId != null && !telegramChatId.isBlank();
    }

    /**
     * 지정 시각이 야간 발송 보류 구간인지.
     *
     * <p>22:00~07:00 처럼 자정을 넘는 구간을 지원해야 하므로 시작이 끝보다 큰 경우를 따로 처리한다.
     */
    public boolean isWithinQuietHours(LocalTime time) {
        if (quietHoursStart == null || quietHoursEnd == null) {
            return false;
        }
        if (quietHoursStart.isBefore(quietHoursEnd)) {
            return !time.isBefore(quietHoursStart) && time.isBefore(quietHoursEnd);
        }
        return !time.isBefore(quietHoursStart) || time.isBefore(quietHoursEnd);
    }
}
