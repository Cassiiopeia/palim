package kr.suhsaechan.palim.automation.influencer.discover;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import kr.suhsaechan.palim.automation.influencer.collect.ChannelCollectService;
import kr.suhsaechan.palim.automation.influencer.domain.DiscoverySource;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannel;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannelRepository;
import kr.suhsaechan.palim.automation.influencer.taxonomy.TaxonomyProvider;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeClient;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubePage;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeVideoData;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채널 발굴 — 세 경로.
 *
 * <p>주력은 <b>키워드 검색</b>이다. 광고를 걸 만한 채널은 대개 구독 5만~50만의 중견 크리에이터인데,
 * 인기 차트에는 이미 뜬 대형·기업·음악 채널이 올라와서 이들이 거의 안 잡힌다. 검색은 호출당
 * 100 units 로 비싸지만 롱테일 키워드로 정확히 이 층을 뽑아낸다.
 *
 * <p>인기 차트(1 unit)와 추천 채널 확장(1 unit)은 보조다. 싸므로 매일 돌려 누적하면 시간이
 * 지날수록 커버리지가 넓어진다.
 *
 * <p>모든 경로가 할당량 초과를 만나면 <b>그 자리에서 멈추고 커서를 남긴다.</b> 예외를 위로
 * 던지지 않는 이유는, 발굴이 멈춰도 그날의 지표 갱신·채점은 계속되어야 하기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscoveryService {

    private final YoutubeClient youtubeClient;
    private final ChannelCollectService collectService;
    private final DiscoveryCursorRepository cursorRepository;
    private final InfluencerChannelRepository channelRepository;
    private final TaxonomyProvider taxonomyProvider;
    private final Clock clock;

    /**
     * 시드 키워드 검색.
     *
     * @param keywordLimit 이번 실행에서 돌릴 키워드 수. 검색 예산(units)을 100 으로 나눈 값이다
     * @return 신규 등록 채널 수
     */
    @Transactional
    public int discoverByKeywords(int keywordLimit) {
        syncKeywordCursors();

        List<DiscoveryCursor> cursors = cursorRepository.findNextTargets(
                DiscoverySource.KEYWORD_SEARCH, Limit.of(keywordLimit));

        int registered = 0;
        for (DiscoveryCursor cursor : cursors) {
            try {
                YoutubePage<String> page = youtubeClient.searchChannelIds(
                        cursor.getCursorKey(), cursor.getPageToken());
                int found = collectService.registerAll(page.items(), DiscoverySource.KEYWORD_SEARCH);
                cursor.advance(page.nextPageToken(), found, Instant.now(clock));
                registered += found;

            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.YOUTUBE_QUOTA_EXCEEDED) {
                    log.info("검색 예산 소진 — 키워드 발굴 중단, 다음 실행에서 재개");
                    break;
                }
                log.error("키워드 발굴 실패 — {}", cursor.getCursorKey(), e);
            }
        }
        log.info("키워드 발굴 완료 — 신규 {}건", registered);
        return registered;
    }

    /**
     * 국내 인기 차트 순회.
     *
     * <p>호출당 1 unit 이라 카테고리 전체를 매일 돌아도 부담이 없다. 매일 다른 영상이 올라오므로
     * <b>누적하면 커버리지가 저절로 넓어진다.</b>
     */
    @Transactional
    public int discoverByPopularChart(List<String> youtubeCategoryIds) {
        int registered = 0;
        for (String categoryId : youtubeCategoryIds) {
            try {
                List<YoutubeVideoData> videos = youtubeClient.fetchPopularVideos(categoryId, 50);
                List<String> channelIds = videos.stream()
                        .map(YoutubeVideoData::channelId)
                        .filter(id -> id != null && !id.isBlank())
                        .distinct()
                        .toList();
                registered += collectService.registerAll(channelIds, DiscoverySource.POPULAR_CHART);

            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.YOUTUBE_QUOTA_EXCEEDED) {
                    log.info("할당량 소진 — 차트 발굴 중단");
                    break;
                }
                log.error("차트 발굴 실패 — 카테고리 {}", categoryId, e);
            }
        }
        log.info("차트 발굴 완료 — 신규 {}건", registered);
        return registered;
    }

    /**
     * 추천 채널 확장.
     *
     * <p>크리에이터끼리 걸어둔 링크를 타고 인접 채널로 넓힌다. 큐레이션을 안 하는 채널이 많아
     * 수확은 들쭉날쭉하지만 1 unit 이라 밑질 게 없다.
     */
    @Transactional
    public int expandByFeaturedChannels(int channelLimit) {
        List<InfluencerChannel> seeds = channelRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, channelLimit)).getContent();

        int registered = 0;
        for (InfluencerChannel seed : seeds) {
            try {
                List<String> featured =
                        youtubeClient.fetchFeaturedChannelIds(seed.getYoutubeChannelId());
                registered += collectService.registerAll(featured, DiscoverySource.FEATURED_CHANNEL);

            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.YOUTUBE_QUOTA_EXCEEDED) {
                    log.info("할당량 소진 — 추천 채널 확장 중단");
                    break;
                }
                log.error("추천 채널 확장 실패 — {}", seed.getYoutubeChannelId(), e);
            }
        }
        log.info("추천 채널 확장 완료 — 신규 {}건", registered);
        return registered;
    }

    /** 발주사가 올린 채널 목록을 시드로 등록한다. */
    @Transactional
    public int registerManualSeeds(List<String> youtubeChannelIds) {
        return collectService.registerAll(youtubeChannelIds, DiscoverySource.MANUAL_SEED);
    }

    /**
     * 설정의 시드 키워드와 커서를 맞춘다.
     *
     * <p>화면에서 키워드를 추가하면 커서가 자동으로 생긴다. 삭제된 키워드의 커서는 지우지 않는다 —
     * 그 키워드로 몇 명을 발굴했는지가 판단 근거로 남아야 하고, 순회 대상에서만 빠지면 된다.
     */
    private void syncKeywordCursors() {
        for (String keyword : taxonomyProvider.allSeedKeywords()) {
            if (cursorRepository.findBySourceAndCursorKey(
                    DiscoverySource.KEYWORD_SEARCH, keyword).isEmpty()) {
                cursorRepository.save(
                        DiscoveryCursor.of(DiscoverySource.KEYWORD_SEARCH, keyword));
            }
        }
    }
}
