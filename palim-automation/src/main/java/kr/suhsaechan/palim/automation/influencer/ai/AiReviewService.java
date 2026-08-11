package kr.suhsaechan.palim.automation.influencer.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.suhsaechan.palim.automation.influencer.comment.VideoComment;
import kr.suhsaechan.palim.automation.influencer.comment.VideoCommentRepository;
import kr.suhsaechan.palim.automation.influencer.domain.Campaign;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelCategoryRepository;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannel;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerScore;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerScoreRepository;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideo;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideoRepository;
import kr.suhsaechan.palim.automation.influencer.scoring.Grade;
import kr.suhsaechan.palim.automation.influencer.scoring.ScoringPropertiesProvider;
import kr.suhsaechan.palim.automation.influencer.transcript.TranscriptProvider;
import kr.suhsaechan.palim.automation.influencer.transcript.TranscriptResult;
import kr.suhsaechan.palim.automation.influencer.transcript.VideoTranscript;
import kr.suhsaechan.palim.automation.influencer.transcript.VideoTranscriptRepository;
import kr.suhsaechan.palim.automation.influencer.youtube.CommentOrder;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeClient;
import kr.suhsaechan.palim.common.config.ConfigReader;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 심층 심사 오케스트레이션.
 *
 * <p>흐름: 자막·댓글 수집 → 입력 조립 → 지문 대조 → AI 호출 → 검증 → 저장.
 *
 * <p><b>지문이 같으면 호출하지 않는다.</b> 재현성이 목적이다 — 같은 채널을 다시 열었을 때
 * 점수가 달라 보이면 그 순간 신뢰를 잃는다.
 *
 * <p>자막 실패는 정상 분기다. 메타+댓글만으로 심사를 계속하고 AI 가 스스로 신뢰도를 낮춘다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReviewService {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Seoul"));

    private final YoutubeClient youtubeClient;
    private final TranscriptProvider transcriptProvider;
    private final InfluencerAiClient aiClient;
    private final InfluencerVideoRepository videoRepository;
    private final VideoTranscriptRepository transcriptRepository;
    private final VideoCommentRepository commentRepository;
    private final InfluencerScoreRepository scoreRepository;
    private final ChannelCategoryRepository categoryRepository;
    private final ScoringPropertiesProvider scoringProperties;
    private final AiCallGuard callGuard;
    private final ConfigReader config;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 한 채널을 심사한다.
     *
     * @return 심사를 실행했으면 true, 지문이 같아 건너뛰었으면 false
     */
    @Transactional
    public boolean review(Campaign campaign, InfluencerChannel channel) {
        InfluencerScore score = scoreRepository
                .findByCampaignIdAndChannelId(campaign.getId(), channel.getId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INFLUENCER_CHANNEL_NOT_FOUND, channel.getYoutubeChannelId()));

        List<InfluencerVideo> videos = videoRepository
                .findByChannelIdAndShortFormFalseOrderByPublishedAtDesc(channel.getId(),
                        Limit.of(config.getInt(AiConfigKeys.VIDEOS_PER_CHANNEL)));
        if (videos.isEmpty()) {
            log.info("분석할 롱폼이 없어 심사를 건너뜁니다 — {}", channel.getYoutubeChannelId());
            return false;
        }

        collectMaterials(videos);
        ReviewInput input = buildInput(channel, campaign, videos);

        String hash = InputHash.of(channel.getId().toString(), campaign.getId().toString(),
                config.getString(AiConfigKeys.PROMPT_VERSION),
                scoringProperties.rubricVersion(), input.groundingSources());

        if (!score.needsAiReview(hash)) {
            log.debug("입력이 그대로라 AI 재호출을 건너뜁니다 — {}", channel.getYoutubeChannelId());
            return false;
        }

        AiScorePoints points = points();
        AiReviewResult raw = callWithRetry(input, points, channel.getYoutubeChannelId());
        AiReviewResult validated = AiReviewValidator.validate(raw, input.groundingSources(),
                input.hasTranscript(), points);

        BigDecimal aiTotal = BigDecimal.valueOf(validated.total())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = score.getRuleTotal().add(aiTotal);
        Grade grade = Grade.of(total.doubleValue(), scoringProperties.current().grade());

        score.applyAiResult(aiTotal, objectMapper.writeValueAsString(validated), hash, grade,
                Instant.now(clock));
        return true;
    }

    /**
     * 캠페인 상위 N명 심사. 한 채널 실패가 나머지를 멈추지 않는다.
     *
     * <p>진입 시 <b>캠페인 단위 쿨다운</b>을 건다. 캠페인별로 거는 이유는 한 캠페인의 연타가
     * 다른 캠페인의 정상 실행을 막지 않게 하기 위해서다.
     */
    @Transactional
    public int reviewTopCandidates(Campaign campaign) {
        callGuard.ensureAllowed("campaign:" + campaign.getId());

        int topN = config.getInt(AiConfigKeys.REVIEW_TOP_N);
        List<InfluencerScore> targets = scoreRepository
                .findByCampaignIdAndHardFailReasonIsNullOrderByTotalDesc(campaign.getId(),
                        Limit.of(topN));

        int reviewed = 0;
        for (InfluencerScore target : targets) {
            try {
                if (review(campaign, target.getChannel())) {
                    reviewed++;
                }
            } catch (BusinessException e) {
                // 일일 상한에 닿으면 남은 채널은 시도하지 않는다 — 계속 돌아봐야 전부 실패한다.
                if (e.getErrorCode() == ErrorCode.AI_DAILY_LIMIT_EXCEEDED) {
                    log.warn("일일 상한 도달 — 남은 {}건은 다음 기회에",
                            targets.size() - reviewed);
                    break;
                }
                log.error("AI 심사 실패 — 채널 {}",
                        target.getChannel().getYoutubeChannelId(), e);
            } catch (RuntimeException e) {
                log.error("AI 심사 실패 — 채널 {}",
                        target.getChannel().getYoutubeChannelId(), e);
            }
        }
        log.info("AI 심사 완료 — 캠페인 {}, 대상 {}건 중 {}건 신규 심사",
                campaign.getName(), targets.size(), reviewed);
        return reviewed;
    }

    // ==================================================================
    // 내부
    // ==================================================================

    /**
     * 스키마 위반은 재시도 1회. 두 번째도 실패하면 예외를 올려 미평가로 남긴다.
     *
     * <p>재시도도 <b>일일 상한을 다시 확인하고 원장에 기록한다</b>. 재시도가 공짜라고 착각하면
     * 상한이 실제 사용량의 절반만 세게 되고, 형식 오류가 반복되는 상황에서 정확히 그만큼
     * 초과된다.
     */
    private AiReviewResult callWithRetry(ReviewInput input, AiScorePoints points, String channelId) {
        try {
            return call(input, points);
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.AI_RESPONSE_INVALID) {
                throw e;
            }
            log.warn("AI 응답 형식 오류 — 1회 재시도 {}", channelId);
            return call(input, points);
        }
    }

    /** 호출 1건 = 확인 → 호출 → 기록. 실패해도 기록한다 — 요금은 이미 발생했다. */
    private AiReviewResult call(ReviewInput input, AiScorePoints points) {
        callGuard.ensureDailyBudget();
        try {
            return aiClient.review(input, points);
        } finally {
            callGuard.record();
        }
    }

    /**
     * 자막·댓글 수집.
     *
     * <p>자막은 이미 시도한 영상을 다시 시도하지 않는다 — 실패도 행으로 남아 있으므로,
     * 반복 시도로 차단을 자초하지 않는다.
     */
    private void collectMaterials(List<InfluencerVideo> videos) {
        Instant now = Instant.now(clock);
        int commentsPerVideo = config.getInt(AiConfigKeys.COMMENTS_PER_VIDEO);
        int maxChars = config.getInt(AiConfigKeys.TRANSCRIPT_MAX_CHARS);

        for (InfluencerVideo video : videos) {
            if (transcriptRepository.findByVideoId(video.getId()).isEmpty()) {
                TranscriptResult result = transcriptProvider.fetch(video.getYoutubeVideoId());
                if (result.hasContent() && result.content().length() > maxChars) {
                    result = new TranscriptResult(result.status(), result.language(),
                            result.content().substring(0, maxChars));
                }
                transcriptRepository.save(VideoTranscript.of(video, result, now));
            }

            if (!commentRepository.findByVideoIdIn(List.of(video.getId())).isEmpty()) {
                continue;
            }
            for (CommentOrder order : CommentOrder.values()) {
                try {
                    youtubeClient.fetchComments(video.getYoutubeVideoId(), order, commentsPerVideo)
                            .forEach(comment -> commentRepository.save(VideoComment.of(video, order,
                                    comment.text(), comment.likeCount(), comment.publishedAt(), now)));
                } catch (BusinessException e) {
                    // 댓글 차단·할당량 소진 모두 심사를 막지 않는다. 자료가 적으면 AI 가
                    // 신뢰도를 낮춘다.
                    log.info("댓글 수집 실패 — {} ({})", video.getYoutubeVideoId(),
                            e.getErrorCode());
                }
            }
        }
    }

    private ReviewInput buildInput(InfluencerChannel channel, Campaign campaign,
                                   List<InfluencerVideo> videos) {
        List<UUID> videoIds = videos.stream().map(InfluencerVideo::getId).toList();

        Map<UUID, VideoTranscript> transcripts = transcriptRepository.findByVideoIdIn(videoIds)
                .stream()
                .collect(Collectors.toMap(transcript -> transcript.getVideo().getId(),
                        transcript -> transcript));

        Map<UUID, List<VideoComment>> comments = commentRepository.findByVideoIdIn(videoIds)
                .stream()
                .collect(Collectors.groupingBy(comment -> comment.getVideo().getId()));

        List<ReviewInput.VideoInput> videoInputs = new ArrayList<>();
        for (InfluencerVideo video : videos) {
            VideoTranscript transcript = transcripts.get(video.getId());
            List<VideoComment> videoComments = comments.getOrDefault(video.getId(), List.of());

            videoInputs.add(new ReviewInput.VideoInput(
                    video.getTitle(),
                    DATE.format(video.getPublishedAt()),
                    video.isPaidPromotion(),
                    transcript != null && transcript.hasContent() ? transcript.getContent() : null,
                    toCommentInputs(videoComments, CommentOrder.TIME),
                    toCommentInputs(videoComments, CommentOrder.RELEVANCE)));
        }

        List<String> labels = categoryRepository.findByChannelId(channel.getId()).stream()
                .map(category -> category.getCategoryCode())
                .toList();

        // 댓글이 하나도 안 모였으면 차단으로 본다 — 브랜드 안전성 신호다.
        boolean commentsDisabled = comments.isEmpty();

        return new ReviewInput(channel.getTitle(), null, labels,
                new ReviewInput.CampaignBrief(campaign.getName(), campaign.getProductCategory(),
                        campaign.getTargetAudience(), campaign.getSellingPoints(),
                        campaign.getExclusions()),
                videoInputs, commentsDisabled);
    }

    private List<ReviewInput.CommentInput> toCommentInputs(List<VideoComment> comments,
                                                          CommentOrder order) {
        return comments.stream()
                .filter(comment -> comment.getSortSource() == order)
                .map(comment -> new ReviewInput.CommentInput(comment.getContent(),
                        comment.getLikeCount()))
                .toList();
    }

    private AiScorePoints points() {
        return new AiScorePoints(
                config.getDouble(AiConfigKeys.POINTS_BRAND_SAFETY),
                config.getDouble(AiConfigKeys.POINTS_CAMPAIGN_FIT),
                config.getDouble(AiConfigKeys.POINTS_AUDIENCE_QUALITY));
    }
}
