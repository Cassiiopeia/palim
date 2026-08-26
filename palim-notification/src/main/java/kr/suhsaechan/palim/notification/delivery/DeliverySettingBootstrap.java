package kr.suhsaechan.palim.notification.delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발송 설정 초기화.
 *
 * <p>메일 서버 정보는 여기서 채우지 않는다. 화면에서 넣어야 하며, 넣기 전까지 메일은 쌓이기만
 * 하고 나가지 않는다 — 텔레그램이 이미 그렇게 동작한다.
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class DeliverySettingBootstrap implements ApplicationRunner {

    private final DeliverySettingService deliverySettingService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        deliverySettingService.initializeIfAbsent();
        if (deliverySettingService.canSendMail()) {
            log.info("발송 설정 준비 — 메일 사용 가능");
        } else {
            log.info("발송 설정 준비 — 메일 서버가 아직 등록되지 않았습니다. "
                    + "화면에서 넣기 전까지 메일은 쌓이기만 합니다.");
        }
    }
}
