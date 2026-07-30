package kr.suhsaechan.palim.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 설정 초기화.
 *
 * <p>텔레그램 연결 정보는 여기서 채우지 않는다. 발주자가 웹에서 등록해야 하며, 등록 전에는
 * 알림이 Outbox 에 쌓이기만 하고 발송되지 않는다.
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class NotificationBootstrap implements ApplicationRunner {

    private final NotificationSettingService notificationSettingService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        NotificationSetting setting = notificationSettingService.initializeIfAbsent();

        if (setting.isTelegramConnected()) {
            log.info("알림 설정 준비 — 발송 방식 {}, 일일 리포트 {}",
                    setting.getOrderAlertMode(),
                    setting.isDailyReportEnabled() ? setting.getDailyReportTime() : "사용 안 함");
        } else {
            log.warn("알림 설정 준비 — 텔레그램이 연결되지 않았습니다. "
                    + "웹 관리자에서 연결하기 전까지 알림은 발송되지 않고 대기합니다.");
        }
    }
}
