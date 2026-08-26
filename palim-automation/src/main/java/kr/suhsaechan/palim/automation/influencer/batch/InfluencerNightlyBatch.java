package kr.suhsaechan.palim.automation.influencer.batch;

import static kr.suhsaechan.palim.automation.influencer.batch.InfluencerBatchConfigKeys.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kr.suhsaechan.palim.automation.influencer.collect.ChannelCollectService;
import kr.suhsaechan.palim.automation.influencer.discover.DiscoveryService;
import kr.suhsaechan.palim.automation.influencer.domain.Campaign;
import kr.suhsaechan.palim.automation.influencer.domain.CampaignRepository;
import kr.suhsaechan.palim.automation.influencer.domain.CampaignStatus;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelStatus;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannel;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannelRepository;
import kr.suhsaechan.palim.automation.influencer.domain.RefreshTier;
import kr.suhsaechan.palim.automation.influencer.score.ScoringService;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeQuotaService;
import kr.suhsaechan.palim.automation.influencer.InfluencerFeature;
import kr.suhsaechan.palim.common.config.ConfigReader;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * 야간 일괄 처리 — 발굴 → 갱신 → 채점.
 *
 * <p>순서에 이유가 있다. 발굴을 먼저 해야 그날 새로 찾은 채널이 같은 실행에서 지표까지 채워지고,
 * 채점을 마지막에 둬야 갱신된 최신 지표로 점수가 매겨진다.
 *
 * <p><b>할당량 초과는 실패가 아니다.</b> 각 단계가 자기 자리에서 멈추고 커서를 남기므로, 다음 날
 * 이어서 진행된다. 발굴이 예산을 다 써도 갱신·채점은 계속 시도한다 — 갱신은 훨씬 싸고, 이미
 * 수집된 데이터로 채점하는 것은 할당량을 전혀 쓰지 않는다.
 *
 * <p>기본값은 <b>꺼짐</b>이다. API 키 등록과 시드 키워드 확정 전에 돌면 빈 호출로 예산만 태운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InfluencerNightlyBatch {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final DiscoveryService discoveryService;
    private final ChannelCollectService collectService;
    private final ScoringService scoringService;
    private final InfluencerChannelRepository channelRepository;
    private final CampaignRepository campaignRepository;
    private final YoutubeQuotaService quotaService;
    private final ConfigReader config;
    private final InfluencerFeature influencerFeature;
    private final Clock clock;

    /** 새벽 3시. 할당량이 태평양 표준시 자정에 초기화되므로 그 이후에 돈다. */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void run() {
        // 마스터 스위치가 꺼져 있으면 아무것도 하지 않는다. 아래 세부 설정보다 «앞» 에 두는
        // 이유는, 세부만 켜 두고 기능 전체를 껐을 때 이 하나가 살아 도는 일을 막기 위해서다.
        if (!influencerFeature.isEnabled()) {
            log.debug("인플루언서 기능 꺼짐 — {} 건너뜀", "야간 배치");
            return;
        }

        if (!config.getBoolean(ENABLED)) {
            log.debug("인플루언서 야간 배치 — 사용 안 함");
            return;
        }

        log.info("인플루언서 야간 배치 시작 — 잔여 할당량 {} units", quotaService.remainingUnits());

        int discovered = discover();
        int refreshed = refreshTiers();
        int scored = scoreActiveCampaigns();

        log.info("인플루언서 야간 배치 완료 — 발굴 {}건, 갱신 {}건, 채점 {}건, 잔여 {} units",
                discovered, refreshed, scored, quotaService.remainingUnits());
    }

    // ==================================================================
    // 단계
    // ==================================================================

    private int discover() {
        int total = 0;
        try {
            total += discoveryService.discoverByKeywords(config.getInt(KEYWORD_LIMIT));
            total += discoveryService.discoverByPopularChart(
                    config.getObject(CHART_CATEGORY_IDS, STRING_LIST));
            total += discoveryService.expandByFeaturedChannels(config.getInt(FEATURED_LIMIT));
        } catch (RuntimeException e) {
            log.error("발굴 단계 실패 — 다음 단계는 계속 진행한다", e);
        }
        return total;
    }

    /**
     * 티어별 갱신.
     *
     * <p>라이징부터 채운다. 전체 예산이 부족할 때 어느 채널을 포기할지의 우선순위가 여기서
     * 정해지며, 폭발 조짐이 있는 채널을 놓치는 것이 가장 비싸다.
     */
    private int refreshTiers() {
        int limit = config.getInt(REFRESH_LIMIT);
        List<InfluencerChannel> targets = new ArrayList<>();

        targets.addAll(dueChannels(RefreshTier.RISING, config.getInt(TIER_RISING_HOURS), limit));
        targets.addAll(dueChannels(RefreshTier.HOT, config.getInt(TIER_HOT_HOURS),
                limit - targets.size()));
        targets.addAll(dueChannels(RefreshTier.WARM, config.getInt(TIER_WARM_HOURS),
                limit - targets.size()));
        targets.addAll(dueChannels(RefreshTier.COLD, config.getInt(TIER_COLD_HOURS),
                limit - targets.size()));

        int refreshed = 0;
        for (InfluencerChannel channel : targets) {
            try {
                collectService.refresh(channel);
                refreshed++;
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.YOUTUBE_QUOTA_EXCEEDED) {
                    log.info("할당량 소진 — 갱신 중단 ({}건 완료)", refreshed);
                    break;
                }
                log.error("채널 갱신 실패 — {}", channel.getYoutubeChannelId(), e);
            } catch (RuntimeException e) {
                log.error("채널 갱신 실패 — {}", channel.getYoutubeChannelId(), e);
            }
        }
        return refreshed;
    }

    private List<InfluencerChannel> dueChannels(RefreshTier tier, int intervalHours, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Instant threshold = Instant.now(clock).minus(Duration.ofHours(intervalHours));
        return channelRepository.findRefreshTargets(tier, threshold, Limit.of(limit));
    }

    /** 진행 중 캠페인만 채점한다. 할당량을 쓰지 않으므로 항상 끝까지 돈다. */
    private int scoreActiveCampaigns() {
        List<Campaign> campaigns = campaignRepository.findByStatus(CampaignStatus.ACTIVE);
        if (campaigns.isEmpty()) {
            return 0;
        }

        List<InfluencerChannel> channels = channelRepository.findAll().stream()
                .filter(channel -> channel.getStatus() == ChannelStatus.ACTIVE)
                .toList();

        int scored = 0;
        for (Campaign campaign : campaigns) {
            scored += scoringService.scoreAll(campaign, channels);
        }
        return scored;
    }
}
