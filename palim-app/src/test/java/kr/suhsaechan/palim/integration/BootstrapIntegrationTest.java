package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import kr.suhsaechan.palim.auth.AdminAccountService;
import kr.suhsaechan.palim.channel.ChannelService;
import kr.suhsaechan.palim.channel.StockPushSetting;
import kr.suhsaechan.palim.channel.StockPushSettingService;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.notification.NotificationSetting;
import kr.suhsaechan.palim.notification.NotificationSettingService;
import kr.suhsaechan.palim.notification.OrderAlertMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 기동 시 부트스트랩 초기화가 수행되는지 검증한다.
 *
 * <p>#4 에서 남긴 문제 — 단일 행 설정이 초기화되지 않으면 F-02·F-08 이 동작하지 않는다 —
 * 가 해소되었는지 확인하는 테스트다.
 */
class BootstrapIntegrationTest extends IntegrationTest {

    @Autowired
    private ChannelService channelService;

    @Autowired
    private StockPushSettingService stockPushSettingService;

    @Autowired
    private NotificationSettingService notificationSettingService;

    @Autowired
    private AdminAccountService adminAccountService;

    @Test
    @DisplayName("채널 7개가 등록된다")
    void 채널이_전부_등록된다() {
        assertThat(channelService.findAll())
                .extracting("code")
                .containsExactlyInAnyOrder(ChannelCode.values());
    }

    @Test
    @DisplayName("채널은 비활성 상태로 등록된다 — 인증정보 없이 수집이 시작되면 안 된다")
    void 채널은_비활성으로_시작한다() {
        assertThat(channelService.findEnabled()).isEmpty();
    }

    @Test
    @DisplayName("채널별 기본 수집 주기가 명세서대로 설정된다")
    void 수집_주기가_설정된다() {
        assertThat(channelService.getByCode(ChannelCode.COUPANG).getCollectIntervalSeconds())
                .isEqualTo(300);
        assertThat(channelService.getByCode(ChannelCode.NAVER).getCollectIntervalSeconds())
                .isEqualTo(300);
        assertThat(channelService.getByCode(ChannelCode.ESM).getCollectIntervalSeconds())
                .isEqualTo(600);
    }

    @Test
    @DisplayName("재고 전송 설정은 안전한 쪽으로 초기화된다")
    void 재고_전송은_안전하게_초기화된다() {
        StockPushSetting setting = stockPushSettingService.get();

        assertThat(setting.isEnabled()).as("전송은 비활성으로 시작해야 한다").isFalse();
        assertThat(setting.isSimulationMode()).as("시뮬레이션은 활성으로 시작해야 한다").isTrue();
        assertThat(setting.getMaxDeltaPerPush()).isPositive();
    }

    @Test
    @DisplayName("알림 설정이 명세서 기본값으로 초기화된다")
    void 알림_설정이_초기화된다() {
        NotificationSetting setting = notificationSettingService.get();

        assertThat(setting.getOrderAlertMode()).isEqualTo(OrderAlertMode.IMMEDIATE);
        assertThat(setting.isDailyReportEnabled()).isTrue();
        assertThat(setting.getDailyReportTime()).hasToString("09:00");
        assertThat(setting.getLowStockRepeatHours()).isEqualTo(24);
        assertThat(setting.isTelegramConnected())
                .as("텔레그램은 발주자가 웹에서 연결한다").isFalse();
    }

    @Test
    @DisplayName("관리자 계정이 생성된다")
    void 관리자_계정이_생성된다() {
        assertThat(adminAccountService.exists("admin")).isTrue();
        assertThat(adminAccountService.getByUsername("admin").getPasswordHash())
                .as("평문이 아니라 인코딩된 해시가 저장되어야 한다")
                .isNotEqualTo("test-admin-password")
                .startsWith("{");
    }
}
