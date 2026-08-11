package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import kr.suhsaechan.palim.automation.influencer.domain.Campaign;
import kr.suhsaechan.palim.automation.influencer.domain.CampaignRepository;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelSnapshot;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelSnapshotRepository;
import kr.suhsaechan.palim.automation.influencer.domain.DiscoverySource;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannel;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannelRepository;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerScore;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerScoreRepository;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideo;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideoRepository;
import kr.suhsaechan.palim.automation.influencer.scoring.Grade;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 인플루언서 스키마·엔티티 정합 검증.
 *
 * <p>{@code ddl-auto=validate} 가 전체 컨텍스트에서 매핑을 검사하므로 컨텍스트가 뜨는 것
 * 자체가 1차 검증이다. 그 위에서 도메인 규칙(쇼츠 자동 판정·중복 방지·하루 한 스냅샷)을
 * 실제 DB 로 확인한다.
 */
class InfluencerSchemaIntegrationTest extends IntegrationTest {

    @Autowired
    private InfluencerChannelRepository channels;

    @Autowired
    private InfluencerVideoRepository videos;

    @Autowired
    private ChannelSnapshotRepository snapshots;

    @Autowired
    private CampaignRepository campaigns;

    @Autowired
    private InfluencerScoreRepository scores;

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    private InfluencerChannel saveChannel(String youtubeId) {
        return channels.save(InfluencerChannel.register(
                youtubeId, "채널A", "@channel-a", "KR", "UU-" + youtubeId,
                DiscoverySource.KEYWORD_SEARCH));
    }

    private Campaign saveCampaign(String name) {
        return campaigns.save(Campaign.of(
                name, "생활용품", "30대", "소구점", "금지 조건",
                50_000, 300_000, 10_000, 1_000_000));
    }

    @Test
    @DisplayName("채널을 저장하고 유튜브 ID 로 조회한다")
    void 채널_저장_조회() {
        saveChannel("ch-1");

        assertThat(channels.findByYoutubeChannelId("ch-1")).isPresent();
    }

    @Test
    @DisplayName("같은 유튜브 채널 ID 는 두 번 저장되지 않는다 — 발굴 경로가 겹쳐도 한 행이다")
    void 채널_중복_저장_불가() {
        saveChannel("ch-2");

        assertThatThrownBy(() -> channels.saveAndFlush(InfluencerChannel.register(
                "ch-2", "채널B", null, "KR", null, DiscoverySource.POPULAR_CHART)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("60초 이하 영상은 쇼츠로 자동 판정된다 — 지표 오염의 최대 원인이다")
    void 쇼츠_자동_판정() {
        InfluencerChannel channel = saveChannel("ch-3");

        InfluencerVideo shortForm = videos.save(InfluencerVideo.of(
                channel, "v-short", "쇼츠", NOW, 45, 1_000_000, 10, 1, false, NOW));
        InfluencerVideo longForm = videos.save(InfluencerVideo.of(
                channel, "v-long", "롱폼", NOW, 300, 10_000, 500, 100, false, NOW));

        assertThat(shortForm.isShortForm()).isTrue();
        assertThat(longForm.isShortForm()).isFalse();
        assertThat(videos.findByChannelIdAndShortFormFalseOrderByPublishedAtDesc(
                channel.getId(), org.springframework.data.domain.Limit.of(10)))
                .extracting(InfluencerVideo::getYoutubeVideoId)
                .containsExactly("v-long");
    }

    @Test
    @DisplayName("영상은 스코어링 입력으로 변환된다")
    void 영상_샘플_변환() {
        InfluencerChannel channel = saveChannel("ch-5");
        InfluencerVideo video = videos.save(InfluencerVideo.of(
                channel, "v-sample", "롱폼", NOW, 300, 10_000, 500, 100, true, NOW));

        assertThat(video.toSample().viewCount()).isEqualTo(10_000);
        assertThat(video.toSample().paidPromotion()).isTrue();
    }

    @Test
    @DisplayName("채널당 하루 스냅샷은 한 행이다 — 배치가 여러 번 돌아도 곡선이 왜곡되지 않는다")
    void 스냅샷_하루_한행() {
        InfluencerChannel channel = saveChannel("ch-4");
        LocalDate day = LocalDate.of(2026, 8, 11);
        snapshots.save(ChannelSnapshot.of(channel, day, 50_000, 1_000_000, 120));

        assertThatThrownBy(() -> snapshots.saveAndFlush(
                ChannelSnapshot.of(channel, day, 50_100, 1_010_000, 121)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("캠페인은 스코어링 입력(CampaignTarget)으로 변환된다")
    void 캠페인_타깃_변환() {
        Campaign campaign = saveCampaign("테스트 캠페인");

        assertThat(campaign.toTarget().targetReachMin()).isEqualTo(50_000);
        assertThat(campaign.toTarget().subscriberMax()).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("점수의 세부 배점은 jsonb 로 저장되고 그대로 읽힌다")
    void 점수_jsonb_저장() {
        Campaign campaign = saveCampaign("점수 캠페인");
        InfluencerChannel channel = saveChannel("ch-6");
        String breakdown = "{\"reach\":14.0,\"vsr\":14.0,\"momentum\":14.0}";

        InfluencerScore saved = scores.save(InfluencerScore.of(
                campaign, channel, new BigDecimal("70.00"), breakdown, "", Grade.A,
                2_500_000L, new BigDecimal("50.00"), "v1", NOW));

        // jsonb 는 PostgreSQL 이 키 순서·공백을 정규화하므로 문자열이 아니라 구조로 비교한다.
        String stored = scores.findByCampaignIdAndChannelId(campaign.getId(), channel.getId())
                .orElseThrow()
                .getRuleBreakdown();
        assertThat(new tools.jackson.databind.ObjectMapper().readTree(stored))
                .isEqualTo(new tools.jackson.databind.ObjectMapper().readTree(breakdown));
        assertThat(saved.getAiTotal()).isNull();
        assertThat(saved.needsAiReview("hash-1")).isTrue();
    }

    @Test
    @DisplayName("AI 결과를 반영하면 총점이 룰+AI 가 된다")
    void AI_결과_반영() {
        Campaign campaign = saveCampaign("AI 캠페인");
        InfluencerChannel channel = saveChannel("ch-7");
        InfluencerScore score = scores.save(InfluencerScore.of(
                campaign, channel, new BigDecimal("60.00"), "{}", "", Grade.B,
                1_000_000L, new BigDecimal("20.00"), "v1", NOW));

        score.applyAiResult(new BigDecimal("25.00"), "{\"safety\":12.0}", "hash-1", Grade.A, NOW);
        scores.saveAndFlush(score);

        InfluencerScore reloaded = scores.findById(score.getId()).orElseThrow();
        assertThat(reloaded.getTotal()).isEqualByComparingTo("85.00");
        assertThat(reloaded.needsAiReview("hash-1")).isFalse();
        assertThat(reloaded.needsAiReview("hash-2")).isTrue();
    }

    @Test
    @DisplayName("등급표는 총점순과 CPV 효율순 두 가지로 정렬된다")
    void 등급표_정렬() {
        Campaign campaign = saveCampaign("정렬 캠페인");
        InfluencerChannel high = saveChannel("ch-high");
        InfluencerChannel cheap = saveChannel("ch-cheap");

        // 총점은 높지만 단가 대비 효율은 나쁜 채널
        scores.save(InfluencerScore.of(campaign, high, new BigDecimal("68.00"), "{}", "", Grade.A,
                8_000_000L, new BigDecimal("400.00"), "v1", NOW));
        // 총점은 낮지만 CPV 가 훨씬 좋은 채널 — 사장님이 실제로 사야 할 쪽
        scores.save(InfluencerScore.of(campaign, cheap, new BigDecimal("60.00"), "{}", "", Grade.B,
                600_000L, new BigDecimal("30.00"), "v1", NOW));

        var byTotal = scores.findByCampaignIdAndHardFailReasonIsNullOrderByTotalDesc(
                campaign.getId(), org.springframework.data.domain.Limit.of(10));
        var byCpv = scores.findByCampaignIdAndHardFailReasonIsNullOrderByEstimatedCpvAsc(
                campaign.getId(), org.springframework.data.domain.Limit.of(10));

        assertThat(byTotal).first().extracting(s -> s.getChannel().getId()).isEqualTo(high.getId());
        assertThat(byCpv).first().extracting(s -> s.getChannel().getId()).isEqualTo(cheap.getId());
    }
}
