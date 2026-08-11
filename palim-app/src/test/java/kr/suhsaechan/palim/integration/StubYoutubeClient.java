package kr.suhsaechan.palim.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.automation.influencer.youtube.CommentOrder;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeChannelData;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeClient;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeCommentData;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubePage;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeVideoData;

/**
 * 고정 응답 YouTube 클라이언트 — 통합 테스트용.
 *
 * <p>실제 호출 없이 수집→채점 파이프라인 전체를 검증한다. 외부 API 에 의존하는 테스트는
 * 할당량을 소모하고, 남의 채널 데이터가 바뀌면 이유 없이 깨진다.
 *
 * <p>데이터는 전부 합성이다 — 이 저장소는 공개이므로 실존 채널을 넣지 않는다.
 */
public class StubYoutubeClient implements YoutubeClient {

    private final Map<String, YoutubeChannelData> channels = new LinkedHashMap<>();
    private final Map<String, List<YoutubeVideoData>> videosByChannel = new LinkedHashMap<>();

    /** 채널과 영상 N개를 한 번에 등록한다. */
    public StubYoutubeClient withChannel(String channelId, String title, String country,
                                         long subscribers, List<YoutubeVideoData> videos) {
        channels.put(channelId, new YoutubeChannelData(channelId, title, "합성 채널 설명", "@" + channelId,
                country, "UU" + channelId, subscribers, subscribers * 20, videos.size(), false));
        videosByChannel.put(channelId, videos);
        return this;
    }

    /** 롱폼 영상 생성 도우미 — 일정 간격으로 같은 조회수. */
    public static List<YoutubeVideoData> longforms(String channelId, Instant latest, int count,
                                                   long views, long likes, long comments) {
        List<YoutubeVideoData> videos = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            videos.add(new YoutubeVideoData(
                    channelId + "-v" + i, channelId, "합성 영상 " + i, "설명",
                    latest.minus(Duration.ofDays(3L * i)), 600,
                    views, likes, comments, false, false, "22"));
        }
        return videos;
    }

    @Override
    public List<YoutubeChannelData> fetchChannels(List<String> channelIds) {
        return channelIds.stream().map(channels::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public List<YoutubeVideoData> fetchVideos(List<String> videoIds) {
        return videosByChannel.values().stream()
                .flatMap(List::stream)
                .filter(video -> videoIds.contains(video.videoId()))
                .toList();
    }

    @Override
    public YoutubePage<String> fetchUploadedVideoIds(String uploadsPlaylistId, String pageToken) {
        String channelId = uploadsPlaylistId.substring(2); // "UU" 접두사 제거
        List<String> ids = videosByChannel.getOrDefault(channelId, List.of()).stream()
                .map(YoutubeVideoData::videoId)
                .toList();
        return new YoutubePage<>(ids, null);
    }

    @Override
    public List<YoutubeVideoData> fetchPopularVideos(String videoCategoryId, int maxResults) {
        return videosByChannel.values().stream().flatMap(List::stream).limit(maxResults).toList();
    }

    @Override
    public YoutubePage<String> searchChannelIds(String query, String pageToken) {
        return new YoutubePage<>(List.copyOf(channels.keySet()), null);
    }

    @Override
    public List<String> fetchFeaturedChannelIds(String channelId) {
        return List.of();
    }

    @Override
    public List<YoutubeCommentData> fetchComments(String videoId, CommentOrder order, int maxResults) {
        return List.of(new YoutubeCommentData("합성 댓글", 3, Instant.EPOCH, order));
    }
}
