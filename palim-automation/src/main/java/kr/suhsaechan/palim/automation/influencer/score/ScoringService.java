package kr.suhsaechan.palim.automation.influencer.score;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import kr.suhsaechan.palim.automation.influencer.domain.Campaign;
import kr.suhsaechan.palim.automation.influencer.domain.CategoryTaxonomy;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelCategoryRepository;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannel;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerScore;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerScoreRepository;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideo;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideoRepository;
import kr.suhsaechan.palim.automation.influencer.domain.RefreshTier;
import kr.suhsaechan.palim.automation.influencer.scoring.Badge;
import kr.suhsaechan.palim.automation.influencer.scoring.ChannelMetrics;
import kr.suhsaechan.palim.automation.influencer.scoring.CpvEstimate;
import kr.suhsaechan.palim.automation.influencer.scoring.CpvEstimator;
import kr.suhsaechan.palim.automation.influencer.scoring.Grade;
import kr.suhsaechan.palim.automation.influencer.scoring.HardFailReason;
import kr.suhsaechan.palim.automation.influencer.scoring.HardFilter;
import kr.suhsaechan.palim.automation.influencer.scoring.MetricsCalculator;
import kr.suhsaechan.palim.automation.influencer.scoring.RisingIndex;
import kr.suhsaechan.palim.automation.influencer.scoring.RisingIndexCalculator;
import kr.suhsaechan.palim.automation.influencer.scoring.RuleScore;
import kr.suhsaechan.palim.automation.influencer.scoring.RuleScorer;
import kr.suhsaechan.palim.automation.influencer.scoring.ScoringProperties;
import kr.suhsaechan.palim.automation.influencer.scoring.ScoringPropertiesProvider;
import kr.suhsaechan.palim.automation.influencer.scoring.VideoSample;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 수집된 데이터로 점수를 매긴다 — 계산 엔진과 도메인의 접합부.
 *
 * <p>엔진은 DB 를 모르는 순수 함수이고 도메인은 계산 규칙을 모른다. 그 사이를 잇는 것이 여기의
 * 유일한 책임이다. 덕분에 배점 규칙은 엔진 단위 테스트로, 저장 규칙은 통합 테스트로 각각
 * 독립 검증된다.
 *
 * <p><b>설정은 채점할 때마다 다시 읽는다.</b> 화면에서 가중치를 바꾸면 재기동 없이 다음 채점부터
 * 반영되어야 하기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoringService {

    private final InfluencerVideoRepository videoRepository;
    private final InfluencerScoreRepository scoreRepository;
    private final ChannelCategoryRepository categoryRepository;
    private final ScoringPropertiesProvider propertiesProvider;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 캠페인 기준으로 한 채널을 채점한다.
     *
     * <p>하드 탈락이어도 행은 남긴다 — "왜 후보에 없는지"를 화면에서 설명할 수 있어야 하고,
     * 탈락 사유가 사라지면 사람이 같은 채널을 반복해서 다시 검토하게 된다.
     */
    @Transactional
    public InfluencerScore score(Campaign campaign, InfluencerChannel channel) {
        ScoringProperties props = propertiesProvider.current();
        Instant now = Instant.now(clock);

        List<VideoSample> samples = videoRepository
                .findByChannelIdOrderByPublishedAtDesc(channel.getId(),
                        Limit.of(props.windowSize() * 2))
                .stream()
                .map(InfluencerVideo::toSample)
                .toList();

        ChannelMetrics metrics = MetricsCalculator.calculate(
                samples, channel.getSubscriberCount(), now, props);

        Optional<HardFailReason> hardFail = HardFilter.check(metrics, channel.getSubscriberCount(),
                campaign.toTarget(), channel.isExcluded(), props);

        RuleScore ruleScore = RuleScorer.score(metrics, channel.getSubscriberCount(),
                campaign.toTarget(), props);
        RisingIndex rising = RisingIndexCalculator.calculate(metrics, channel.getSubscriberCount(),
                props);

        java.util.Set<Badge> badges = new java.util.LinkedHashSet<>(ruleScore.badges());
        if (rising.risingBadge()) {
            badges.add(Badge.RISING);
            // 폭발 조짐이 있는 채널은 매일 본다 — 며칠만 늦어도 단가가 오른다.
            channel.changeTier(RefreshTier.RISING);
        }

        CpvEstimate cpv = CpvEstimator.estimate(channel.getSubscriberCount(),
                primaryCategoryCode(channel), metrics.medianViews(), props.cpv());

        BigDecimal ruleTotal = round(ruleScore.total());
        Grade grade = Grade.of(ruleScore.total(), props.grade());
        String breakdownJson = toJson(ruleScore.breakdown(), rising);
        String badgeText = badges.stream().map(Enum::name).collect(Collectors.joining(","));

        InfluencerScore score = scoreRepository
                .findByCampaignIdAndChannelId(campaign.getId(), channel.getId())
                .orElse(null);

        if (score == null) {
            score = InfluencerScore.of(campaign, channel, ruleTotal, breakdownJson, badgeText,
                    grade, cpv.estimatedPrice(), round(cpv.estimatedCpv()),
                    propertiesProvider.rubricVersion(), now);
        } else {
            score.updateRuleResult(ruleTotal, breakdownJson, badgeText, grade,
                    cpv.estimatedPrice(), round(cpv.estimatedCpv()), now);
        }
        hardFail.ifPresent(score::markHardFail);

        return scoreRepository.save(score);
    }

    /** 캠페인의 모든 대상 채널을 채점한다. 한 채널 실패가 전체를 멈추지 않는다. */
    @Transactional
    public int scoreAll(Campaign campaign, List<InfluencerChannel> channels) {
        int scored = 0;
        for (InfluencerChannel channel : channels) {
            try {
                score(campaign, channel);
                scored++;
            } catch (RuntimeException e) {
                log.error("채점 실패 — 채널 {}", channel.getYoutubeChannelId(), e);
            }
        }
        log.info("채점 완료 — 캠페인 {}, 대상 {}건 중 {}건", campaign.getName(), channels.size(), scored);
        return scored;
    }

    // ==================================================================
    // 내부
    // ==================================================================

    /** 단가 계수를 고를 자체 카테고리. 다중 라벨이면 첫 번째를 쓴다. */
    private String primaryCategoryCode(InfluencerChannel channel) {
        return categoryRepository.findByChannelId(channel.getId()).stream()
                .filter(category -> category.getTaxonomy() == CategoryTaxonomy.PALIM)
                .map(category -> category.getCategoryCode())
                .findFirst()
                .orElse(null);
    }

    /**
     * 화면이 읽을 세부 배점.
     *
     * <p>룰 항목과 라이징 지수를 한 문서에 담는다. 채널 상세 화면이 "왜 이 점수인지"를 보여줄 때
     * 두 값이 같이 필요하고, 나눠 저장하면 조회가 두 번이 된다.
     */
    private String toJson(java.util.Map<String, Double> ruleBreakdown, RisingIndex rising) {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "rule", ruleBreakdown,
                "rising", java.util.Map.of(
                        "total", rising.total(),
                        "breakdown", rising.breakdown())));
    }

    private static BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
