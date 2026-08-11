package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import kr.suhsaechan.palim.automation.influencer.discover.DiscoveryCursorRepository;
import kr.suhsaechan.palim.automation.influencer.discover.DiscoveryService;
import kr.suhsaechan.palim.automation.influencer.domain.DiscoverySource;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannelRepository;
import kr.suhsaechan.palim.automation.influencer.taxonomy.TaxonomyProvider;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeClient;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Limit;

/** 발굴 경로와 커서 동작 검증. */
@Import(InfluencerDiscoveryIntegrationTest.StubConfig.class)
class InfluencerDiscoveryIntegrationTest extends IntegrationTest {

    private static final Instant LATEST = Instant.parse("2026-08-10T00:00:00Z");

    @TestConfiguration
    static class StubConfig {

        @Bean
        @Primary
        YoutubeClient stubYoutubeClient() {
            return new StubYoutubeClient()
                    .withChannel("ch-a", "합성 채널 가", "KR", 60_000,
                            StubYoutubeClient.longforms("ch-a", LATEST, 10, 30_000, 1_500, 300))
                    .withChannel("ch-b", "합성 채널 나", "KR", 120_000,
                            StubYoutubeClient.longforms("ch-b", LATEST, 10, 40_000, 2_000, 400))
                    // 수동 시드 전용. 다른 경로가 먼저 등록하면 발견 경로 검증이 무의미해진다.
                    .withChannel("ch-manual", "합성 채널 다", "KR", 80_000, List.of());
        }
    }

    @Autowired
    private DiscoveryService discoveryService;

    @Autowired
    private DiscoveryCursorRepository cursors;

    @Autowired
    private InfluencerChannelRepository channels;

    @Autowired
    private TaxonomyProvider taxonomyProvider;

    @Test
    @DisplayName("카테고리 체계가 설정에서 읽힌다")
    void 카테고리_체계() {
        assertThat(taxonomyProvider.categories()).hasSize(15);
        assertThat(taxonomyProvider.byCode()).containsKey("beauty");
        assertThat(taxonomyProvider.coefficients().get("beauty")).isEqualTo(45.0);
        // 롱테일 키워드여야 중견 채널이 잡힌다
        assertThat(taxonomyProvider.allSeedKeywords()).contains("캠핑 장비 추천", "이유식 만들기");
    }

    @Test
    @DisplayName("시드 키워드마다 커서가 생기고 아직 안 돌린 키가 먼저 나온다")
    void 키워드_커서() {
        discoveryService.discoverByKeywords(3);

        // 요청한 3개만 돌았어도 커서는 전체 키워드에 대해 만들어진다.
        // 커서가 없으면 매번 앞쪽 키워드만 검색되어 뒤쪽은 영영 순서를 못 받는다.
        assertThat(cursors.count()).isGreaterThan(100);
        // 다음 실행은 아직 안 돌린 키부터 집는다
        assertThat(cursors.findNextTargets(DiscoverySource.KEYWORD_SEARCH, Limit.of(3)))
                .allSatisfy(cursor -> assertThat(cursor.getLastRunAt()).isNull());
    }

    @Test
    @DisplayName("인기 차트로 발굴한 채널도 등록된다")
    void 차트_발굴() {
        discoveryService.discoverByPopularChart(List.of("22", "26"));

        assertThat(channels.findByYoutubeChannelId("ch-a")).isPresent();
    }

    @Test
    @DisplayName("수동 시드로 올린 채널은 발견 경로가 MANUAL_SEED 로 남는다")
    void 수동_시드() {
        discoveryService.registerManualSeeds(List.of("ch-manual"));

        assertThat(channels.findByYoutubeChannelId("ch-manual"))
                .get()
                .extracting(channel -> channel.getDiscoverySource())
                .isEqualTo(DiscoverySource.MANUAL_SEED);
    }
}
