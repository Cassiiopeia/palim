package kr.suhsaechan.palim.notification;

import java.time.LocalTime;
import kr.suhsaechan.palim.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 설정 서비스 (F-02, F-05, F-06).
 *
 * <p>웹에서 변경하면 재시작 없이 즉시 반영된다. 발송 시점마다 이 설정을 조회하기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class NotificationSettingService {

    private final NotificationSettingRepository notificationSettingRepository;

    /** 설정이 없으면 기능 명세서가 정의한 기본값으로 만든다. 부트스트랩에서 호출한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public NotificationSetting initializeIfAbsent() {
        return notificationSettingRepository.findFirstByOrderByCreatedAtAsc()
                .orElseGet(() -> notificationSettingRepository.save(
                        NotificationSetting.createDefault()));
    }

    @Transactional(readOnly = true)
    public NotificationSetting get() {
        return notificationSettingRepository.findFirstByOrderByCreatedAtAsc()
                .orElseThrow(() -> new BusinessException(
                        NotificationErrorCode.NOTIFICATION_SETTING_NOT_INITIALIZED));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void connectTelegram(String telegramChatId) {
        get().connectTelegram(telegramChatId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changeOrderAlertMode(OrderAlertMode mode, int batchIntervalMinutes) {
        get().changeOrderAlertMode(mode, batchIntervalMinutes);
    }

    /** 야간 발송 보류 시간대. null 두 개를 넘기면 사용하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void changeQuietHours(LocalTime start, LocalTime end) {
        get().changeQuietHours(start, end);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changeDailyReport(boolean enabled, LocalTime time) {
        get().changeDailyReport(enabled, time);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changeLowStockRepeatHours(int hours) {
        get().changeLowStockRepeatHours(hours);
    }
}
