package kr.suhsaechan.palim.web.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import kr.suhsaechan.palim.channel.Channel;
import kr.suhsaechan.palim.common.ChannelCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 수집 상태 판정 단위 테스트.
 *
 * <p>판정 순서(자동 중단 → 비활성 → 실패 → 대기 → 지연 → 정상)가 이 화면의 전부다.
 * 순서가 틀리면 "시스템이 멈춘 것"이 "발주자가 끈 것"으로 보인다.
 */
class CollectStatusViewTest {

    private static final int THRESHOLD = 3;
    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");

    private static Channel channel(int intervalSeconds) {
        return Channel.register(ChannelCode.COUPANG, intervalSeconds);
    }

    @Test
    void 등록만_된_채널은_비활성이다() {
        assertThat(CollectStatusView.of(channel(300), THRESHOLD, NOW).health())
                .isEqualTo(CollectHealth.DISABLED);
    }

    @Test
    @DisplayName("연속 실패로 꺼진 채널은 비활성이 아니라 자동 중단이다")
    void 자동_중단_구분() {
        Channel channel = channel(300);
        channel.enable();
        for (int i = 0; i < THRESHOLD; i++) {
            channel.recordCollectFailure(NOW.minusSeconds(600), "인증 실패");
        }
        channel.disable();   // CollectStateService 가 임계 도달 시 수행하는 동작

        assertThat(CollectStatusView.of(channel, THRESHOLD, NOW).health())
                .isEqualTo(CollectHealth.AUTO_DISABLED);
    }

    @Test
    void 마지막_수집이_실패면_실패다() {
        Channel channel = channel(300);
        channel.enable();
        channel.recordCollectFailure(NOW.minusSeconds(60), "타임아웃");

        assertThat(CollectStatusView.of(channel, THRESHOLD, NOW).health())
                .isEqualTo(CollectHealth.FAILING);
    }

    @Test
    void 활성인데_수집_기록이_없으면_첫_수집_대기다() {
        Channel channel = channel(300);
        channel.enable();

        assertThat(CollectStatusView.of(channel, THRESHOLD, NOW).health())
                .isEqualTo(CollectHealth.WAITING_FIRST);
    }

    @Test
    void 최근에_성공했으면_정상이다() {
        Channel channel = channel(300);
        channel.enable();
        channel.recordCollectSuccess(NOW.minusSeconds(120), NOW.minusSeconds(60));

        assertThat(CollectStatusView.of(channel, THRESHOLD, NOW).health())
                .isEqualTo(CollectHealth.HEALTHY);
    }

    @Test
    @DisplayName("예정 시각 + 여유를 넘기면 지연 — 실패 기록 없이 멈춘 스케줄러를 잡는다")
    void 지연_판정() {
        Channel channel = channel(60);
        channel.enable();
        channel.recordCollectSuccess(NOW.minus(Duration.ofMinutes(21)),
                NOW.minus(Duration.ofMinutes(20)));

        // 예정 = 20분 전 + 60초, 여유 = max(120초, 5분) = 5분 → 훨씬 지났다
        assertThat(CollectStatusView.of(channel, THRESHOLD, NOW).health())
                .isEqualTo(CollectHealth.STALE);
    }

    @Test
    @DisplayName("주기가 짧아도 5분 여유 안이면 지연이 아니다 — 스케줄러 틱 밀림 오판 방지")
    void 지연_여유_하한() {
        Channel channel = channel(60);
        channel.enable();
        channel.recordCollectSuccess(NOW.minus(Duration.ofMinutes(5)),
                NOW.minus(Duration.ofMinutes(4)));

        // 예정 = 4분 전 + 60초 = 3분 전. 여유 5분 안 → 정상
        assertThat(CollectStatusView.of(channel, THRESHOLD, NOW).health())
                .isEqualTo(CollectHealth.HEALTHY);
    }

    @Test
    void 다음_수집_표기() {
        Channel channel = channel(600);
        channel.enable();
        channel.recordCollectSuccess(NOW.minusSeconds(120), NOW.minusSeconds(60));

        CollectStatusView view = CollectStatusView.of(channel, THRESHOLD, NOW);
        assertThat(view.nextDueText()).isEqualTo("9분 후");
        assertThat(view.cursorLagText()).isEqualTo("2분 전까지 수집됨");
    }
}
