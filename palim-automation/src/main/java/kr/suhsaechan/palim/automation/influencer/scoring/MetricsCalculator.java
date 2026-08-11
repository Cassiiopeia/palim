package kr.suhsaechan.palim.automation.influencer.scoring;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** {@link VideoSample} 목록 → {@link ChannelMetrics}. 순수 함수 — DB·API 의존 없음. */
public final class MetricsCalculator {

    private MetricsCalculator() {
    }

    public static ChannelMetrics calculate(
            List<VideoSample> videos, long subscriberCount, Instant now, ScoringProperties props) {

        List<VideoSample> longform = videos.stream()
                .filter(v -> v.durationSeconds() > props.shortsMaxSeconds())
                .sorted(Comparator.comparing(VideoSample::publishedAt).reversed())
                .limit(props.windowSize())
                .toList();

        if (longform.isEmpty()) {
            return new ChannelMetrics(0, 0, 0, 0, 0, 1.0, 1.0, 1.0, 0, Long.MAX_VALUE, 0, 1.0, 1.0);
        }

        double[] views = longform.stream().mapToDouble(VideoSample::viewCount).sorted().toArray();
        double medianViews = median(views);
        double vsr = subscriberCount > 0 ? medianViews / subscriberCount : 0;

        int weight = props.rule().engagement().commentWeight();
        double engagementRate = median(longform.stream()
                .filter(v -> v.viewCount() > 0)
                .mapToDouble(v -> (v.likeCount() + (double) weight * v.commentCount()) / v.viewCount())
                .sorted().toArray());

        double q1 = quantile(views, 0.25);
        double q3 = quantile(views, 0.75);
        double cv = medianViews > 0 ? (q3 - q1) / medianViews : 0;

        boolean enough = longform.size() >= 20;
        double trendRatio = enough ? ratio(viewsMedian(longform, 0, 10), viewsMedian(longform, 10, 20)) : 1.0;
        double crashRatio = enough ? ratio(viewsMedian(longform, 0, 5), viewsMedian(longform, 5, 20)) : 1.0;
        double peakRatio = enough ? peakRatio(longform) : 1.0;

        int uploads90d = (int) longform.stream()
                .filter(v -> Duration.between(v.publishedAt(), now).toDays() <= 90)
                .count();
        long daysSinceLastUpload = Duration.between(longform.getFirst().publishedAt(), now).toDays();

        double paidRatio = longform.stream().filter(VideoSample::paidPromotion).count()
                / (double) longform.size();

        double velocityRatio = enough
                ? ratio(vpdMedian(longform, 0, 5, now), vpdMedian(longform, 5, 20, now))
                : 1.0;
        double burstRatio = enough
                ? ratio(commentRateMedian(longform, 0, 5), commentRateMedian(longform, 0, longform.size()))
                : 1.0;

        return new ChannelMetrics(longform.size(), medianViews, vsr, engagementRate, cv,
                trendRatio, peakRatio, crashRatio, uploads90d, daysSinceLastUpload, paidRatio,
                velocityRatio, burstRatio);
    }

    /** 최근 50개 창 안에서 "연속 5개 중앙값"의 최대(=피크) 대비 최근 5개 중앙값. 단발 떡상 오탐 방지. */
    private static double peakRatio(List<VideoSample> longform) {
        double recent = viewsMedian(longform, 0, 5);
        double peak = 0;
        for (int i = 0; i + 5 <= longform.size(); i++) {
            peak = Math.max(peak, viewsMedian(longform, i, i + 5));
        }
        return ratio(recent, peak);
    }

    private static double viewsMedian(List<VideoSample> videos, int from, int to) {
        return median(videos.subList(from, Math.min(to, videos.size())).stream()
                .mapToDouble(VideoSample::viewCount).sorted().toArray());
    }

    private static double vpdMedian(List<VideoSample> videos, int from, int to, Instant now) {
        return median(videos.subList(from, Math.min(to, videos.size())).stream()
                .mapToDouble(v -> v.viewCount()
                        / (double) Math.max(1, Duration.between(v.publishedAt(), now).toDays()))
                .sorted().toArray());
    }

    private static double commentRateMedian(List<VideoSample> videos, int from, int to) {
        return median(videos.subList(from, Math.min(to, videos.size())).stream()
                .filter(v -> v.viewCount() > 0)
                .mapToDouble(v -> v.commentCount() / (double) v.viewCount())
                .sorted().toArray());
    }

    private static double ratio(double numerator, double denominator) {
        return denominator > 0 ? numerator / denominator : 1.0;
    }

    /** sorted 배열의 중앙값. */
    private static double median(double[] sorted) {
        return quantile(sorted, 0.5);
    }

    /** sorted 배열의 분위수 — 선형 보간 방식. */
    private static double quantile(double[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }
}
