package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import kr.suhsaechan.palim.automation.influencer.collect.ChannelCollectService;
import kr.suhsaechan.palim.automation.influencer.domain.Campaign;
import kr.suhsaechan.palim.automation.influencer.domain.CampaignRepository;
import kr.suhsaechan.palim.automation.influencer.domain.DiscoverySource;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannel;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannelRepository;
import kr.suhsaechan.palim.automation.influencer.domain.RefreshTier;
import kr.suhsaechan.palim.automation.influencer.rising.RisingSignalRepository;
import kr.suhsaechan.palim.automation.influencer.rising.RisingSignalService;
import kr.suhsaechan.palim.automation.influencer.score.ScoringService;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeClient;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 라이징 감지·해제 검증.
 *
 * <p>채점이 신호 테이블에 반영되고, 감지된 채널이 매일 갱신 티어로 올라가는지 본다.
 */
@Import(RisingSignalIntegrationTest.StubConfig.class)
class RisingSignalIntegrationTest extends IntegrationTest {

    private static final Instant LATEST = Instant.parse("2026-08-10T00:00:00Z");

    @TestConfiguration
    static class StubConfig {

        @Bean
        @Primary
        YoutubeClient stubYoutubeClient() {
            StubYoutubeClient stub = new StubYoutubeClient();

            // 폭발 중인 채널.
            //
            // 라이징의 핵심 신호는 "조회수가 구독자를 넘어서는 상태"다 — 알고리즘이 비구독자에게
            // 뿌리고 있다는 뜻이며, 구독자 유입이 그 뒤를 따른다. 그래서 구독 3만에 조회수가
            // 5만~12만이 되도록 잡는다(VSR 2.8). 최근 10편이 직전 10편보다 크게 잘 돌아
            // 가속도 신호도 함께 잡히는 구성이다.
            var hot = new java.util.ArrayList<>(
                    StubYoutubeClient.longforms("ch-hot-recent", LATEST, 10, 120_000, 8_000, 1_600));
            hot.addAll(StubYoutubeClient.longforms("ch-hot-old",
                    LATEST.minus(java.time.Duration.ofDays(40)), 10, 50_000, 2_500, 400));
            stub.withChannel("ch-hot", "합성 급상승 채널", "KR", 30_000, hot);

            // 정체 채널: 조회수가 구독자의 20% 수준으로 평범하다
            stub.withChannel("ch-flat", "합성 정체 채널", "KR", 200_000,
                    StubYoutubeClient.longforms("ch-flat", LATEST, 25, 40_000, 1_200, 200));

            return stub;
        }
    }

    @Autowired
    private ChannelCollectService collectService;

    @Autowired
    private ScoringService scoringService;

    @Autowired
    private RisingSignalService risingSignalService;

    @Autowired
    private RisingSignalRepository signals;

    @Autowired
    private InfluencerChannelRepository channels;

    @Autowired
    private CampaignRepository campaigns;

    private Campaign activeCampaign(String name) {
        Campaign campaign = Campaign.of(name, "생활용품", "30대", "소구점", "금지 조건",
                30_000, 200_000, 10_000, 1_000_000);
        campaign.activate();
        return campaigns.save(campaign);
    }

    private InfluencerChannel prepare(String youtubeId) {
        collectService.registerAll(List.of(youtubeId), DiscoverySource.KEYWORD_SEARCH);
        InfluencerChannel channel = channels.findByYoutubeChannelId(youtubeId).orElseThrow();
        collectService.refresh(channel);
        return channel;
    }

    @Test
    @DisplayName("폭발 중인 채널은 라이징으로 감지되고 매일 갱신 대상이 된다")
    void 감지() {
        Campaign campaign = activeCampaign("라이징 캠페인");
        InfluencerChannel channel = prepare("ch-hot");

        scoringService.score(campaign, channel);

        var signal = signals.findByChannelId(channel.getId());
        assertThat(signal).isPresent();
        assertThat(signal.get().isActive()).isTrue();
        // 규모 대비 몇 배로 도는지가 화면의 첫 숫자다
        assertThat(signal.get().getArbitrageRatio().doubleValue()).isGreaterThan(1.0);
        // 며칠만 늦어도 단가가 오르므로 매일 본다
        assertThat(channel.getRefreshTier()).isEqualTo(RefreshTier.RISING);
    }

    @Test
    @DisplayName("평범한 채널은 신호가 생기지 않는다")
    void 미감지() {
        Campaign campaign = activeCampaign("정체 캠페인");
        InfluencerChannel channel = prepare("ch-flat");

        scoringService.score(campaign, channel);

        assertThat(signals.findByChannelId(channel.getId())).isEmpty();
        assertThat(risingSignalService.findActive(10))
                .extracting(s -> s.getChannel().getYoutubeChannelId())
                .doesNotContain("ch-flat");
    }

    @Test
    @DisplayName("재채점해도 최초 감지 시각은 유지된다 — 며칠째인지가 판단에 직결된다")
    void 감지일_유지() {
        Campaign campaign = activeCampaign("재채점 캠페인");
        InfluencerChannel channel = prepare("ch-hot");

        scoringService.score(campaign, channel);
        Instant first = signals.findByChannelId(channel.getId()).orElseThrow().getDetectedAt();

        scoringService.score(campaign, channel);
        var reloaded = signals.findByChannelId(channel.getId()).orElseThrow();

        assertThat(reloaded.getDetectedAt()).isEqualTo(first);
        // 재평가 시각은 갱신된다
        assertThat(reloaded.getEvaluatedAt()).isAfterOrEqualTo(first);
    }
}
