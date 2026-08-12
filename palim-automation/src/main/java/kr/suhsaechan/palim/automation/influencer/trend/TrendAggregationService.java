package kr.suhsaechan.palim.automation.influencer.trend;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.suhsaechan.palim.automation.influencer.domain.CategoryTaxonomy;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelCategory;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelCategoryRepository;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideo;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideoRepository;
import kr.suhsaechan.palim.common.config.ConfigReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주간 트렌드 집계.
 *
 * <p>수집 코퍼스(영상 제목)에서 키워드를 뽑아 주 단위로 센다. <b>AI 를 쓰지 않는다</b> —
 * 순수 문자열 빈도 계산이라 비용이 0이고, 오히려 AI 를 태우면 같은 입력에 다른 결과가 나와
 * 주간 비교가 흔들린다.
 *
 * <p>주 단위인 이유는 요일별 업로드 편차 때문이다. 크리에이터는 주말에 몰아 올리는 경향이 있어
 * 일 단위로 보면 요일이 트렌드처럼 보인다.
 *
 * <p>카테고리별로 따로 센다. 전체 집계만 하면 규모가 큰 카테고리(게임·음악)의 말이 상위를
 * 독점해 정작 발주사가 보는 분야의 변화가 묻힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendAggregationService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final InfluencerVideoRepository videoRepository;
    private final ChannelCategoryRepository categoryRepository;
    private final TrendKeywordRepository trendRepository;
    private final KeywordExtractor keywordExtractor;
    private final ConfigReader config;
    private final Clock clock;

    /**
     * 지난 한 주를 집계한다.
     *
     * @return 저장된 키워드 수
     */
    @Transactional
    public int aggregateLastWeek() {
        LocalDate weekStart = lastWeekStart();
        return aggregate(weekStart);
    }

    /**
     * 지정한 주를 집계한다.
     *
     * <p>재집계는 갱신이다 — 같은 주를 다시 돌려도 행이 늘지 않는다. 수집이 늦게 들어온 영상이
     * 반영되도록 재실행을 허용한다.
     */
    @Transactional
    public int aggregate(LocalDate weekStart) {
        Instant from = weekStart.atStartOfDay(ZONE).toInstant();
        Instant to = weekStart.plusWeeks(1).atStartOfDay(ZONE).toInstant();

        List<InfluencerVideo> videos = videoRepository.findByPublishedAtBetween(from, to);
        if (videos.isEmpty()) {
            log.info("트렌드 집계 — {} 주에 수집된 영상이 없다", weekStart);
            return 0;
        }

        Map<UUID, List<String>> categoriesByChannel = categoriesByChannel(videos);

        // (카테고리, 키워드) -> 빈도
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        for (InfluencerVideo video : videos) {
            List<String> keywords = keywordExtractor.extract(video.getTitle());
            if (keywords.isEmpty()) {
                continue;
            }
            List<String> categories = categoriesByChannel
                    .getOrDefault(video.getChannel().getId(), List.of());

            // 전체 집계는 항상 하고, 카테고리별 집계는 라벨이 있을 때만 한다.
            countInto(counts, TrendKeyword.ALL_CATEGORIES, keywords);
            for (String category : categories) {
                countInto(counts, category, keywords);
            }
        }

        int minFrequency = config.getInt(TrendConfigKeys.MIN_FREQUENCY);
        Map<String, Map<String, Integer>> previous = previousWeekCounts(weekStart.minusWeeks(1));

        int saved = 0;
        for (var categoryEntry : counts.entrySet()) {
            String category = categoryEntry.getKey();
            Map<String, Integer> prevKeywords =
                    previous.getOrDefault(category, Map.of());

            for (var keywordEntry : categoryEntry.getValue().entrySet()) {
                // 한두 번 나온 말은 트렌드가 아니라 잡음이다. 저장하면 보드가 쓰레기로 찬다.
                if (keywordEntry.getValue() < minFrequency) {
                    continue;
                }
                int prev = prevKeywords.getOrDefault(keywordEntry.getKey(), 0);
                upsert(weekStart, category, keywordEntry.getKey(), keywordEntry.getValue(), prev);
                saved++;
            }
        }

        log.info("트렌드 집계 완료 — {} 주, 영상 {}건에서 키워드 {}건", weekStart, videos.size(), saved);
        return saved;
    }

    /** 이번 주 급상승 키워드 — 발굴 시드 환류와 트렌드 배지가 쓴다. */
    @Transactional(readOnly = true)
    public List<TrendKeyword> findRising(String categoryCode, int limit) {
        LocalDate week = trendRepository.findLatestWeekStart().orElse(null);
        if (week == null) {
            return List.of();
        }
        double minGrowth = config.getDouble(TrendConfigKeys.RISING_MIN_GROWTH);

        return trendRepository
                .findByWeekStartAndCategoryCodeOrderByFrequencyDesc(week, categoryCode,
                        org.springframework.data.domain.Limit.of(limit * 5))
                .stream()
                .filter(keyword -> keyword.growthRatio() >= minGrowth)
                .sorted((a, b) -> Double.compare(b.growthRatio(), a.growthRatio()))
                .limit(limit)
                .toList();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<LocalDate> latestWeek() {
        return trendRepository.findLatestWeekStart();
    }

    @Transactional(readOnly = true)
    public List<TrendKeyword> findTop(LocalDate weekStart, String categoryCode, int limit) {
        return trendRepository.findByWeekStartAndCategoryCodeOrderByFrequencyDesc(
                weekStart, categoryCode, org.springframework.data.domain.Limit.of(limit));
    }

    /** 이번 주(진행 중)가 아니라 <b>완결된 지난 주</b>를 집계한다. */
    public LocalDate lastWeekStart() {
        return LocalDate.now(clock.withZone(ZONE))
                .with(DayOfWeek.MONDAY)
                .minusWeeks(1);
    }

    // ==================================================================
    // 내부
    // ==================================================================

    private void countInto(Map<String, Map<String, Integer>> counts, String category,
                           List<String> keywords) {
        Map<String, Integer> bucket = counts.computeIfAbsent(category, key -> new HashMap<>());
        for (String keyword : keywords) {
            bucket.merge(keyword, 1, Integer::sum);
        }
    }

    private Map<String, Map<String, Integer>> previousWeekCounts(LocalDate weekStart) {
        Map<String, Map<String, Integer>> previous = new LinkedHashMap<>();
        for (TrendKeyword keyword : trendRepository.findByWeekStart(weekStart)) {
            previous.computeIfAbsent(keyword.getCategoryCode(), key -> new HashMap<>())
                    .put(keyword.getKeyword(), keyword.getFrequency());
        }
        return previous;
    }

    private void upsert(LocalDate weekStart, String category, String keyword, int frequency,
                        int prevFrequency) {
        trendRepository.findByWeekStartAndCategoryCodeAndKeyword(weekStart, category, keyword)
                .ifPresentOrElse(
                        existing -> existing.update(frequency, prevFrequency),
                        () -> trendRepository.save(TrendKeyword.of(weekStart, category, keyword,
                                frequency, prevFrequency)));
    }

    /** 채널별 자체 카테고리 라벨. 라벨이 없는 채널은 전체 집계에만 들어간다. */
    private Map<UUID, List<String>> categoriesByChannel(List<InfluencerVideo> videos) {
        Set<UUID> channelIds = videos.stream()
                .map(video -> video.getChannel().getId())
                .collect(Collectors.toSet());

        return categoryRepository.findByChannelIdIn(channelIds).stream()
                .filter(category -> category.getTaxonomy() == CategoryTaxonomy.PALIM)
                .collect(Collectors.groupingBy(
                        category -> category.getChannel().getId(),
                        Collectors.mapping(ChannelCategory::getCategoryCode, Collectors.toList())));
    }

    /** 집계 대상 기간이 지나치게 길어지지 않게 하는 상한(재집계 방어). */
    static Duration maxLookback() {
        return Duration.ofDays(90);
    }
}
