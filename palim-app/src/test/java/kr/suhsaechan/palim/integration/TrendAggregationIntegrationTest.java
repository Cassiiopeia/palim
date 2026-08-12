package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import kr.suhsaechan.palim.automation.influencer.domain.DiscoverySource;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannel;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannelRepository;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideo;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerVideoRepository;
import kr.suhsaechan.palim.automation.influencer.trend.TrendAggregationService;
import kr.suhsaechan.palim.automation.influencer.trend.TrendKeyword;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 주간 트렌드 집계 검증.
 *
 * <p>외부 의존 없이 우리가 수집한 영상 제목만으로 집계가 도는지, 전주 대비 증감이 잡히는지 본다.
 */
class TrendAggregationIntegrationTest extends IntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    /** 월요일. */
    private static final LocalDate THIS_WEEK = LocalDate.of(2026, 8, 3);
    private static final LocalDate LAST_WEEK = THIS_WEEK.minusWeeks(1);

    @Autowired
    private TrendAggregationService trendAggregationService;

    @Autowired
    private InfluencerChannelRepository channels;

    @Autowired
    private InfluencerVideoRepository videos;

    private InfluencerChannel channel(String youtubeId) {
        return channels.save(InfluencerChannel.register(youtubeId, "합성 채널", null, "KR",
                "UU" + youtubeId, DiscoverySource.MANUAL_SEED));
    }

    private void saveVideo(InfluencerChannel channel, String id, String title, LocalDate week,
                           int dayOffset) {
        Instant published = week.atStartOfDay(ZONE).toInstant().plus(Duration.ofDays(dayOffset));
        videos.save(InfluencerVideo.of(channel, id, title, published, 300,
                10_000, 500, 100, false, published));
    }

    @Test
    @DisplayName("영상 제목에서 키워드를 세어 저장한다")
    void 집계() {
        InfluencerChannel channel = channel("ch-trend-1");
        saveVideo(channel, "t1", "겨울 캠핑 난로 추천", THIS_WEEK, 0);
        saveVideo(channel, "t2", "캠핑 난로 비교", THIS_WEEK, 1);
        saveVideo(channel, "t3", "캠핑 난로 설치", THIS_WEEK, 2);

        int saved = trendAggregationService.aggregate(THIS_WEEK);

        assertThat(saved).isPositive();
        assertThat(trendAggregationService.findTop(THIS_WEEK, TrendKeyword.ALL_CATEGORIES, 50))
                .extracting(TrendKeyword::getKeyword)
                // 3회 등장으로 최소 빈도(3)를 충족한다
                .contains("캠핑", "난로", "캠핑 난로");
    }

    @Test
    @DisplayName("최소 등장 횟수 미만은 잡음으로 버린다")
    void 잡음_제거() {
        InfluencerChannel channel = channel("ch-trend-2");
        saveVideo(channel, "t4", "특이한고유명사 등장", THIS_WEEK, 0);
        // 나머지는 다른 말로 채워 최소 빈도를 넘기지 못하게 한다
        saveVideo(channel, "t5", "캠핑 장비 소개", THIS_WEEK, 1);
        saveVideo(channel, "t6", "캠핑 장비 정리", THIS_WEEK, 2);
        saveVideo(channel, "t7", "캠핑 장비 후기", THIS_WEEK, 3);

        trendAggregationService.aggregate(THIS_WEEK);

        List<String> keywords = trendAggregationService
                .findTop(THIS_WEEK, TrendKeyword.ALL_CATEGORIES, 100).stream()
                .map(TrendKeyword::getKeyword)
                .toList();

        assertThat(keywords).contains("캠핑", "장비");
        assertThat(keywords).doesNotContain("특이한고유명사");
    }

    @Test
    @DisplayName("전주 대비 증가가 잡히고 신규 키워드는 따로 표시된다")
    void 증감_비교() {
        InfluencerChannel channel = channel("ch-trend-3");
        // 지난 주: "텐트" 3회
        saveVideo(channel, "p1", "텐트 설치 방법", LAST_WEEK, 0);
        saveVideo(channel, "p2", "텐트 정리 방법", LAST_WEEK, 1);
        saveVideo(channel, "p3", "텐트 세척 방법", LAST_WEEK, 2);
        trendAggregationService.aggregate(LAST_WEEK);

        // 이번 주: "텐트" 6회 + "난로" 신규 3회
        for (int i = 0; i < 6; i++) {
            saveVideo(channel, "c" + i, "텐트 관련 영상 " + i, THIS_WEEK, i % 7);
        }
        for (int i = 0; i < 3; i++) {
            saveVideo(channel, "n" + i, "난로 관련 영상 " + i, THIS_WEEK, i);
        }
        trendAggregationService.aggregate(THIS_WEEK);

        var top = trendAggregationService.findTop(THIS_WEEK, TrendKeyword.ALL_CATEGORIES, 100);

        var tent = top.stream().filter(k -> k.getKeyword().equals("텐트")).findFirst().orElseThrow();
        assertThat(tent.getFrequency()).isEqualTo(6);
        assertThat(tent.getPrevFrequency()).isEqualTo(3);
        assertThat(tent.growthRatio()).isEqualTo(2.0);
        assertThat(tent.isNew()).isFalse();

        var heater = top.stream().filter(k -> k.getKeyword().equals("난로")).findFirst().orElseThrow();
        assertThat(heater.isNew()).isTrue();
    }

    @Test
    @DisplayName("재집계는 행을 늘리지 않는다 — 늦게 들어온 영상을 반영하려면 다시 돌 수 있어야 한다")
    void 재집계() {
        InfluencerChannel channel = channel("ch-trend-4");
        for (int i = 0; i < 4; i++) {
            saveVideo(channel, "r" + i, "백패킹 코스 " + i, THIS_WEEK, i);
        }

        trendAggregationService.aggregate(THIS_WEEK);
        int firstCount = trendAggregationService
                .findTop(THIS_WEEK, TrendKeyword.ALL_CATEGORIES, 100).size();

        trendAggregationService.aggregate(THIS_WEEK);
        int secondCount = trendAggregationService
                .findTop(THIS_WEEK, TrendKeyword.ALL_CATEGORIES, 100).size();

        assertThat(secondCount).isEqualTo(firstCount);
    }
}
