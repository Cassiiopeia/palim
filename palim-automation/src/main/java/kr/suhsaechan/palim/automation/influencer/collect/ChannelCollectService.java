package kr.suhsaechan.palim.automation.influencer.collect;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.suhsaechan.palim.automation.influencer.domain.CategoryTaxonomy;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelCategory;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelCategoryRepository;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelSnapshot;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelSnapshotRepository;
import kr.suhsaechan.palim.automation.influencer.domain.DiscoverySource;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannel;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannelRepository;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideo;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideoRepository;
import kr.suhsaechan.palim.automation.influencer.domain.LabelSource;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeChannelData;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeClient;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeConfigKeys;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubePage;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeVideoData;
import kr.suhsaechan.palim.common.config.ConfigReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채널 등록과 지표 수집.
 *
 * <p>같은 채널이 검색·차트·추천 세 경로로 들어오므로 등록은 항상 <b>upsert</b> 다. 최초 발견
 * 경로만 보존하고 나머지는 갱신한다 — 어느 경로가 실제 성과로 이어졌는지 사후에 평가하기
 * 위해서다.
 *
 * <p>영상도 upsert 다. 조회수는 계속 오르므로 같은 영상을 다시 만나면 행을 늘리지 않고 지표만
 * 덮어쓴다. 추이는 스냅샷과 영상 순서(모멘텀)가 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelCollectService {

    private final YoutubeClient youtubeClient;
    private final InfluencerChannelRepository channelRepository;
    private final InfluencerVideoRepository videoRepository;
    private final ChannelSnapshotRepository snapshotRepository;
    private final ChannelCategoryRepository categoryRepository;
    private final ConfigReader config;
    private final Clock clock;

    /**
     * 채널 ID 목록을 등록한다(이미 있으면 프로필만 갱신).
     *
     * <p>선별 기준을 통과하지 못한 채널은 등록하지 않는다 — 수집 대상이 폭증하면 할당량이
     * 발굴에만 쓰이고 정작 지표 갱신이 멈춘다.
     *
     * @return 새로 등록된 채널 수
     */
    @Transactional
    public int registerAll(List<String> youtubeChannelIds, DiscoverySource source) {
        if (youtubeChannelIds.isEmpty()) {
            return 0;
        }

        List<String> unknown = youtubeChannelIds.stream().distinct()
                .filter(id -> !channelRepository.existsByYoutubeChannelId(id))
                .toList();
        if (unknown.isEmpty()) {
            return 0;
        }

        long minSubscribers = config.getLong(YoutubeConfigKeys.MIN_SUBSCRIBER_COUNT);
        double minKoreanRatio = config.getDouble(YoutubeConfigKeys.MIN_KOREAN_RATIO);

        int registered = 0;
        for (YoutubeChannelData data : youtubeClient.fetchChannels(unknown)) {
            if (!isEligible(data, minSubscribers, minKoreanRatio)) {
                continue;
            }
            InfluencerChannel channel = channelRepository.save(InfluencerChannel.register(
                    data.channelId(), data.title(), data.handle(), data.country(),
                    data.uploadsPlaylistId(), source));
            channel.updateStatistics(data.subscriberCount(), data.viewCount(), data.videoCount(),
                    Instant.now(clock));
            registered++;
        }

        log.info("채널 등록 — 후보 {}건 중 신규 {}건 (경로 {})", unknown.size(), registered, source);
        return registered;
    }

    /**
     * 채널 지표를 새로 읽어 저장한다 — 통계·최근 영상·일별 스냅샷.
     *
     * <p>이 메서드 하나가 채점의 전제를 모두 채운다. 실패하면 다음 주기에 다시 시도하며,
     * 부분 성공을 남기지 않기 위해 한 채널이 한 트랜잭션이다.
     */
    @Transactional
    public void refresh(InfluencerChannel channel) {
        List<YoutubeChannelData> fetched =
                youtubeClient.fetchChannels(List.of(channel.getYoutubeChannelId()));
        if (fetched.isEmpty()) {
            // 삭제·비공개 전환된 채널이다. 갱신 대상에서 빼되 이력은 남긴다.
            log.info("채널 조회 결과 없음 — 휴면 처리 {}", channel.getYoutubeChannelId());
            channel.markDormant();
            return;
        }

        YoutubeChannelData data = fetched.getFirst();
        Instant now = Instant.now(clock);
        channel.updateProfile(data.title(), data.handle(), data.country(),
                data.uploadsPlaylistId());
        channel.updateStatistics(data.subscriberCount(), data.viewCount(), data.videoCount(), now);

        saveSnapshot(channel, data);
        collectVideos(channel, data.uploadsPlaylistId(), now);
    }

    // ==================================================================
    // 내부
    // ==================================================================

    /**
     * 선별 — 구독자 하한과 국내 채널 여부.
     *
     * <p>{@code country} 를 공개하지 않는 채널이 많아 그것만 보면 국내 채널을 대거 놓친다.
     * 한글 비율을 OR 조건으로 둔다.
     */
    private boolean isEligible(YoutubeChannelData data, long minSubscribers, double minKoreanRatio) {
        // 구독자를 숨긴 채널은 도달 효율(VSR)의 분모가 없어 채점 자체가 불가능하다.
        if (data.subscriberCountHidden() || data.subscriberCount() < minSubscribers) {
            return false;
        }
        if ("KR".equalsIgnoreCase(data.country())) {
            return true;
        }
        return KoreanTextRatio.of(data.title(), data.description()) >= minKoreanRatio;
    }

    /** 하루 한 행. 배치가 여러 번 돌아도 성장 곡선이 왜곡되지 않는다. */
    private void saveSnapshot(InfluencerChannel channel, YoutubeChannelData data) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (snapshotRepository.existsByChannelIdAndCapturedOn(channel.getId(), today)) {
            return;
        }
        snapshotRepository.save(ChannelSnapshot.of(channel, today, data.subscriberCount(),
                data.viewCount(), data.videoCount()));
    }

    /**
     * 최근 영상 수집.
     *
     * <p>업로드 재생목록에서 ID 를 모으고(1 unit/페이지) 통계는 50개 배치로 읽는다(1 unit).
     * 영상 50개를 받는 데 3 units 면 충분하다 — 검색 한 번의 1/30 이다.
     */
    private void collectVideos(InfluencerChannel channel, String uploadsPlaylistId, Instant now) {
        if (uploadsPlaylistId == null || uploadsPlaylistId.isBlank()) {
            log.info("업로드 재생목록 없음 — 영상 수집 생략 {}", channel.getYoutubeChannelId());
            return;
        }

        int limit = config.getInt(YoutubeConfigKeys.VIDEO_FETCH_LIMIT);
        List<String> videoIds = new ArrayList<>();
        String pageToken = null;
        do {
            YoutubePage<String> page = youtubeClient.fetchUploadedVideoIds(uploadsPlaylistId, pageToken);
            videoIds.addAll(page.items());
            pageToken = page.nextPageToken();
        } while (pageToken != null && videoIds.size() < limit);

        if (videoIds.size() > limit) {
            videoIds = videoIds.subList(0, limit);
        }
        if (videoIds.isEmpty()) {
            return;
        }

        Map<String, InfluencerVideo> existing = new LinkedHashMap<>();
        videoRepository.findByYoutubeVideoIdIn(videoIds)
                .forEach(video -> existing.put(video.getYoutubeVideoId(), video));

        for (YoutubeVideoData data : youtubeClient.fetchVideos(videoIds)) {
            // 길이를 못 읽으면 쇼츠 판정이 불가능하다. 0 으로 저장하면 전부 쇼츠가 되어
            // 채널 전체가 표본 부족으로 탈락하므로 건너뛴다.
            if (data.durationSeconds() < 0) {
                log.debug("영상 길이 파싱 실패 — 건너뜀 {}", data.videoId());
                continue;
            }
            Optional.ofNullable(existing.get(data.videoId()))
                    .ifPresentOrElse(
                            video -> video.updateStatistics(data.viewCount(), data.likeCount(),
                                    data.commentCount(), data.paidPromotion(), now),
                            () -> videoRepository.save(InfluencerVideo.of(channel, data.videoId(),
                                    data.title(), data.publishedAt(), data.durationSeconds(),
                                    data.viewCount(), data.likeCount(), data.commentCount(),
                                    data.paidPromotion(), now)));
            saveYoutubeCategory(channel, data.categoryId(), now);
        }
    }

    /**
     * 유튜브 원본 카테고리 보관.
     *
     * <p>자체 분류(AI)와 별개로 남긴다 — 분류기를 개선해 재분류할 때 대조 기준이 되고, 인기 차트
     * 순회에도 그대로 쓰인다.
     */
    private void saveYoutubeCategory(InfluencerChannel channel, String categoryId, Instant now) {
        if (categoryId == null || categoryId.isBlank()) {
            return;
        }
        if (categoryRepository.findByChannelIdAndTaxonomyAndCategoryCode(
                channel.getId(), CategoryTaxonomy.YOUTUBE, categoryId).isPresent()) {
            return;
        }
        categoryRepository.save(ChannelCategory.of(channel, CategoryTaxonomy.YOUTUBE, categoryId,
                null, LabelSource.API, now));
    }
}
