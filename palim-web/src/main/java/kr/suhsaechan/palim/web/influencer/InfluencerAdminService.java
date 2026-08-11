package kr.suhsaechan.palim.web.influencer;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.suhsaechan.palim.automation.influencer.domain.Campaign;
import kr.suhsaechan.palim.automation.influencer.domain.CampaignRepository;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelReview;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelReviewRepository;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannel;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannelRepository;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerScore;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerScoreRepository;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideo;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideoRepository;
import kr.suhsaechan.palim.automation.influencer.domain.ReviewDecision;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 등급표 화면용 조회·심사.
 *
 * <p>조회수 중앙값을 점수 행이 아니라 영상에서 다시 계산한다. 점수 행에 캐시해 두면 영상이
 * 갱신됐는데 표는 옛 값을 보여주는 상태가 생기고, 그 어긋남을 사용자는 알 방법이 없다.
 */
@Service
@RequiredArgsConstructor
public class InfluencerAdminService {

    /** 중앙값 계산에 쓰는 롱폼 표본 수. 스코어링 관측 창과 같은 의미다. */
    private static final int MEDIAN_SAMPLE_SIZE = 50;

    private final InfluencerScoreRepository scoreRepository;
    private final InfluencerChannelRepository channelRepository;
    private final InfluencerVideoRepository videoRepository;
    private final ChannelReviewRepository reviewRepository;
    private final CampaignRepository campaignRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Campaign> findCampaigns() {
        return campaignRepository.findAll().stream()
                .sorted(Comparator.comparing(Campaign::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Campaign getCampaign(UUID campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INFLUENCER_CAMPAIGN_NOT_FOUND, campaignId));
    }

    /**
     * 등급표 조회.
     *
     * <p>하드 탈락 건은 목록에서 뺀다 — 채점은 해두되(왜 없는지 설명 가능해야 한다) 후보로는
     * 보여주지 않는다.
     */
    @Transactional(readOnly = true)
    public List<GradeRowView> findGrades(UUID campaignId, GradeSort sort, int limit) {
        List<InfluencerScore> scores = sort == GradeSort.CPV
                ? scoreRepository.findByCampaignIdAndHardFailReasonIsNullOrderByEstimatedCpvAsc(
                        campaignId, Limit.of(limit))
                : scoreRepository.findByCampaignIdAndHardFailReasonIsNullOrderByTotalDesc(
                        campaignId, Limit.of(limit));

        Map<UUID, ReviewDecision> decisions = reviewRepository.findByCampaignId(campaignId).stream()
                .collect(Collectors.toMap(review -> review.getChannel().getId(),
                        ChannelReview::getDecision));

        Map<UUID, Long> medians = new HashMap<>();
        for (InfluencerScore score : scores) {
            medians.put(score.getChannel().getId(), medianViews(score.getChannel().getId()));
        }

        return scores.stream()
                .map(score -> GradeRowView.of(score,
                        decisions.get(score.getChannel().getId()),
                        medians.getOrDefault(score.getChannel().getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ChannelDetailView findChannelDetail(UUID campaignId, UUID channelId) {
        InfluencerChannel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INFLUENCER_CHANNEL_NOT_FOUND, channelId));
        InfluencerScore score = scoreRepository.findByCampaignIdAndChannelId(campaignId, channelId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INFLUENCER_CHANNEL_NOT_FOUND, channelId));
        ChannelReview review = reviewRepository
                .findByCampaignIdAndChannelId(campaignId, channelId).orElse(null);

        List<InfluencerVideo> recent = videoRepository
                .findByChannelIdAndShortFormFalseOrderByPublishedAtDesc(channelId, Limit.of(10));

        return ChannelDetailView.of(channel, score, review, recent, medianViews(channelId));
    }

    /** 심사 판정 기록. 이미 있으면 갱신한다 — 결론이 하나여야 목록이 흔들리지 않는다. */
    @Transactional
    public void review(UUID campaignId, UUID channelId, ReviewDecision decision, String note,
                       String reviewer) {
        Campaign campaign = getCampaign(campaignId);
        InfluencerChannel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INFLUENCER_CHANNEL_NOT_FOUND, channelId));
        Instant now = Instant.now(clock);

        reviewRepository.findByCampaignIdAndChannelId(campaignId, channelId)
                .ifPresentOrElse(
                        existing -> existing.change(decision, note, reviewer, now),
                        () -> reviewRepository.save(ChannelReview.of(campaign, channel, decision,
                                note, reviewer, now)));

        // 제외는 채널 자체를 후보에서 내린다 — 다른 캠페인에서도 다시 보이면 판단이 반복된다.
        if (decision == ReviewDecision.EXCLUDE) {
            channel.exclude(note);
        }
    }

    /** 실제 받은 견적 반영. 추정 CPV 가 실측으로 바뀐다. */
    @Transactional
    public void applyQuotedPrice(UUID campaignId, UUID channelId, long quotedPrice) {
        scoreRepository.findByCampaignIdAndChannelId(campaignId, channelId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INFLUENCER_CHANNEL_NOT_FOUND, channelId))
                .overrideQuotedPrice(quotedPrice);
    }

    /** 심사 진행 현황 — 화면 상단 요약. */
    @Transactional(readOnly = true)
    public Map<ReviewDecision, Long> reviewSummary(UUID campaignId) {
        return reviewRepository.findByCampaignId(campaignId).stream()
                .collect(Collectors.groupingBy(ChannelReview::getDecision,
                        Collectors.counting()));
    }

    // ==================================================================
    // 내부
    // ==================================================================

    /** 롱폼 조회수 중앙값. 쇼츠를 섞으면 자릿수가 달라 표 전체가 무의미해진다. */
    private long medianViews(UUID channelId) {
        long[] views = videoRepository
                .findByChannelIdAndShortFormFalseOrderByPublishedAtDesc(channelId,
                        Limit.of(MEDIAN_SAMPLE_SIZE))
                .stream()
                .mapToLong(InfluencerVideo::getViewCount)
                .sorted()
                .toArray();
        if (views.length == 0) {
            return 0;
        }
        int mid = views.length / 2;
        return views.length % 2 == 1 ? views[mid] : (views[mid - 1] + views[mid]) / 2;
    }

    Map<UUID, InfluencerChannel> channelsById(List<UUID> ids) {
        return channelRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(InfluencerChannel::getId, Function.identity()));
    }
}
