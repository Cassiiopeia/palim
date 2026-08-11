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
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerScore;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideoRepository;
import kr.suhsaechan.palim.automation.influencer.score.ScoringService;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeClient;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeVideoData;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Limit;

/**
 * 수집 → 채점 파이프라인 검증.
 *
 * <p>"채널 하나를 넣으면 점수가 나온다"가 성립하는지 확인한다. 계산 규칙은 엔진 단위 테스트가
 * 이미 검증하므로, 여기서는 <b>연결</b>을 본다 — 수집한 영상이 지표가 되고, 설정이 채점에
 * 반영되고, 결과가 저장되는가.
 */
@Import(InfluencerPipelineIntegrationTest.StubConfig.class)
class InfluencerPipelineIntegrationTest extends IntegrationTest {

    private static final Instant LATEST = Instant.parse("2026-08-10T00:00:00Z");

    @TestConfiguration
    static class StubConfig {

        @Bean
        @Primary
        YoutubeClient stubYoutubeClient() {
            StubYoutubeClient stub = new StubYoutubeClient();

            // 효율 좋은 채널: 구독 5만인데 조회수 중앙값 5만 (VSR 1.0)
            stub.withChannel("ch-efficient", "합성 캠핑 채널", "KR", 50_000,
                    StubYoutubeClient.longforms("ch-efficient", LATEST, 25, 50_000, 3_000, 600));

            // 죽은 채널: 구독 80만인데 조회수 2만 (VSR 0.025)
            stub.withChannel("ch-dead", "합성 대형 채널", "KR", 800_000,
                    StubYoutubeClient.longforms("ch-dead", LATEST, 25, 20_000, 300, 20));

            // 구독자 하한 미달 — 등록 자체가 되지 않아야 한다
            stub.withChannel("ch-tiny", "합성 소형 채널", "KR", 500,
                    StubYoutubeClient.longforms("ch-tiny", LATEST, 25, 1_000, 50, 5));

            // 해외 채널 — 한글 비율도 낮아 선별에서 빠져야 한다
            stub.withChannel("ch-foreign", "Foreign Camping Channel", "US", 300_000,
                    List.<YoutubeVideoData>of());

            return stub;
        }
    }

    @Autowired
    private ChannelCollectService collectService;

    @Autowired
    private ScoringService scoringService;

    @Autowired
    private InfluencerChannelRepository channels;

    @Autowired
    private InfluencerVideoRepository videos;

    @Autowired
    private CampaignRepository campaigns;

    private Campaign activeCampaign() {
        Campaign campaign = Campaign.of("합성 캠페인", "생활용품", "30대", "소구점", "금지 조건",
                30_000, 200_000, 10_000, 1_000_000);
        campaign.activate();
        return campaigns.save(campaign);
    }

    @Test
    @DisplayName("선별 기준을 통과한 채널만 등록된다")
    void 선별() {
        int registered = collectService.registerAll(
                List.of("ch-efficient", "ch-dead", "ch-tiny", "ch-foreign"),
                DiscoverySource.KEYWORD_SEARCH);

        assertThat(registered).isEqualTo(2);
        assertThat(channels.findByYoutubeChannelId("ch-efficient")).isPresent();
        assertThat(channels.findByYoutubeChannelId("ch-dead")).isPresent();
        // 구독자 하한 미달
        assertThat(channels.findByYoutubeChannelId("ch-tiny")).isEmpty();
        // 해외 + 한글 비율 미달
        assertThat(channels.findByYoutubeChannelId("ch-foreign")).isEmpty();
    }

    @Test
    @DisplayName("같은 채널을 다시 등록해도 행이 늘지 않는다 — 발굴 경로가 겹친다")
    void 중복_등록() {
        collectService.registerAll(List.of("ch-efficient"), DiscoverySource.KEYWORD_SEARCH);
        int again = collectService.registerAll(List.of("ch-efficient"), DiscoverySource.POPULAR_CHART);

        assertThat(again).isZero();
    }

    @Test
    @DisplayName("갱신하면 영상과 스냅샷이 저장된다")
    void 수집() {
        collectService.registerAll(List.of("ch-efficient"), DiscoverySource.KEYWORD_SEARCH);
        InfluencerChannel channel = channels.findByYoutubeChannelId("ch-efficient").orElseThrow();

        collectService.refresh(channel);

        assertThat(videos.findByChannelIdOrderByPublishedAtDesc(channel.getId(), Limit.of(100)))
                .hasSize(25);
        assertThat(channel.getSubscriberCount()).isEqualTo(50_000);
    }

    @Test
    @DisplayName("단가 대비 효율이 좋은 채널이 대형 채널보다 높은 점수를 받는다")
    void 채점() {
        Campaign campaign = activeCampaign();
        collectService.registerAll(List.of("ch-efficient", "ch-dead"),
                DiscoverySource.KEYWORD_SEARCH);

        InfluencerChannel efficient = channels.findByYoutubeChannelId("ch-efficient").orElseThrow();
        InfluencerChannel dead = channels.findByYoutubeChannelId("ch-dead").orElseThrow();
        collectService.refresh(efficient);
        collectService.refresh(dead);

        InfluencerScore efficientScore = scoringService.score(campaign, efficient);
        InfluencerScore deadScore = scoringService.score(campaign, dead);

        // 구독자는 16배 차이지만 실제 도달은 2.5배 차이다 — 점수는 효율 쪽이 높아야 한다
        assertThat(efficientScore.getRuleTotal()).isGreaterThan(deadScore.getRuleTotal());
        // 추정 CPV 도 효율 채널이 훨씬 낮다(같은 도달을 싸게 산다)
        assertThat(efficientScore.getEstimatedCpv()).isLessThan(deadScore.getEstimatedCpv());
        assertThat(efficientScore.getRuleBreakdown()).contains("rule").contains("rising");
    }

    @Test
    @DisplayName("재채점은 행을 늘리지 않고 갱신한다")
    void 재채점() {
        Campaign campaign = activeCampaign();
        collectService.registerAll(List.of("ch-efficient"), DiscoverySource.KEYWORD_SEARCH);
        InfluencerChannel channel = channels.findByYoutubeChannelId("ch-efficient").orElseThrow();
        collectService.refresh(channel);

        InfluencerScore first = scoringService.score(campaign, channel);
        InfluencerScore second = scoringService.score(campaign, channel);

        assertThat(second.getId()).isEqualTo(first.getId());
    }
}
