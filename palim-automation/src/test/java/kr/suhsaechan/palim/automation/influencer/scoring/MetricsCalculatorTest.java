package kr.suhsaechan.palim.automation.influencer.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricsCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    /** i일 전 업로드, 조회수 views 인 롱폼(300초) 영상. */
    private static VideoSample longform(int daysAgo, long views, long likes, long comments) {
        return new VideoSample("v-" + daysAgo, NOW.minus(Duration.ofDays(daysAgo)),
                300, views, likes, comments, false);
    }

    private static ScoringProperties props() {
        return ScoringFixtures.defaultProps();
    }

    @Test
    void 쇼츠는_지표에서_제외된다() {
        List<VideoSample> videos = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            videos.add(longform(i * 3, 10_000, 500, 100));
        }
        // 조회수 100만짜리 쇼츠(45초) — 포함되면 중앙값이 왜곡된다
        videos.add(new VideoSample("short-1", NOW.minus(Duration.ofDays(1)), 45, 1_000_000, 0, 0, false));

        ChannelMetrics m = MetricsCalculator.calculate(videos, 50_000, NOW, props());

        assertThat(m.longformCount()).isEqualTo(10);
        assertThat(m.medianViews()).isEqualTo(10_000.0);
    }

    @Test
    void 중앙값과_VSR_과_참여율을_계산한다() {
        // 조회수 8k/9k/10k/11k/12k → 중앙값 10k, 구독 50k → VSR 0.2
        List<VideoSample> videos = List.of(
                longform(5, 8_000, 400, 80), longform(10, 9_000, 450, 90),
                longform(15, 10_000, 500, 100), longform(20, 11_000, 550, 110),
                longform(25, 12_000, 600, 120));

        ChannelMetrics m = MetricsCalculator.calculate(videos, 50_000, NOW, props());

        assertThat(m.medianViews()).isEqualTo(10_000.0);
        assertThat(m.vsr()).isCloseTo(0.2, within(1e-9));
        // 영상별 ER = (500 + 100*3)/10000 = 0.08 전부 동일 → 중앙값 0.08
        assertThat(m.engagementRate()).isCloseTo(0.08, within(1e-9));
    }

    @Test
    void 최근10_대_직전10_추세와_급락_비율을_계산한다() {
        List<VideoSample> videos = new ArrayList<>();
        // 최근 10개(1~28일 전): 20k, 직전 10개(31~58일 전): 10k → trendRatio 2.0
        for (int i = 0; i < 10; i++) {
            videos.add(longform(1 + i * 3, 20_000, 1000, 200));
        }
        for (int i = 0; i < 10; i++) {
            videos.add(longform(31 + i * 3, 10_000, 500, 100));
        }

        ChannelMetrics m = MetricsCalculator.calculate(videos, 50_000, NOW, props());

        assertThat(m.trendRatio()).isCloseTo(2.0, within(1e-9));
        // crashRatio = 최근5 중앙값 20k / 6~20번째 중앙값 — 급락 아님(>1)
        assertThat(m.crashRatio()).isGreaterThan(1.0);
    }

    @Test
    void 표본이_20개_미만이면_추세_비율은_중립값이다() {
        List<VideoSample> videos = List.of(
                longform(5, 10_000, 500, 100), longform(15, 10_000, 500, 100),
                longform(25, 10_000, 500, 100), longform(35, 10_000, 500, 100),
                longform(45, 10_000, 500, 100));

        ChannelMetrics m = MetricsCalculator.calculate(videos, 50_000, NOW, props());

        assertThat(m.trendRatio()).isEqualTo(1.0);
        assertThat(m.peakRatio()).isEqualTo(1.0);
        assertThat(m.crashRatio()).isEqualTo(1.0);
    }

    @Test
    void 활동성_지표를_계산한다() {
        List<VideoSample> videos = List.of(
                longform(3, 10_000, 500, 100), longform(40, 10_000, 500, 100),
                longform(80, 10_000, 500, 100), longform(100, 10_000, 500, 100),
                longform(120, 10_000, 500, 100));

        ChannelMetrics m = MetricsCalculator.calculate(videos, 50_000, NOW, props());

        assertThat(m.uploads90d()).isEqualTo(3);
        assertThat(m.daysSinceLastUpload()).isEqualTo(3);
    }

    @Test
    void 유료광고_비율을_계산한다() {
        List<VideoSample> videos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            videos.add(longform(1 + i * 5, 10_000, 500, 100));
        }
        videos.add(new VideoSample("paid-1", NOW.minus(Duration.ofDays(50)), 300, 10_000, 500, 100, true));
        videos.add(new VideoSample("paid-2", NOW.minus(Duration.ofDays(55)), 300, 10_000, 500, 100, true));

        ChannelMetrics m = MetricsCalculator.calculate(videos, 50_000, NOW, props());

        assertThat(m.paidRatio()).isCloseTo(0.2, within(1e-9));
    }
}
