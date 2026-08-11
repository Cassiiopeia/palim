package kr.suhsaechan.palim.automation.influencer.youtube;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import kr.suhsaechan.palim.common.config.ConfigReader;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;

/**
 * YouTube Data API 호출 구현.
 *
 * <p>모든 호출이 같은 골격을 지킨다: <b>할당량 확인 → 호출 → 소모 기록 → 매핑</b>. 소모 기록을
 * 성공 경로에만 두면 실패한 호출의 quota 가 원장에서 빠져 실제보다 여유가 있다고 오판한다.
 *
 * <p>응답은 {@link JsonNode} 로 받아 필요한 필드만 꺼낸다. 전체를 DTO 로 매핑하면 API 가 필드
 * 하나를 추가·변경할 때마다 역직렬화가 깨지는데, 우리가 쓰는 것은 응답의 극히 일부다.
 */
@Slf4j
@Component
public class YoutubeApiClient implements YoutubeClient {

    /** channels.list·videos.list 의 배치 상한. API 규격이라 설정으로 빼지 않는다. */
    private static final int BATCH_SIZE = 50;

    private final RestClient restClient;
    private final YoutubeProperties properties;
    private final YoutubeQuotaService quotaService;
    private final ConfigReader config;

    public YoutubeApiClient(YoutubeProperties properties, YoutubeQuotaService quotaService,
                            ConfigReader config) {
        this.properties = properties;
        this.quotaService = quotaService;
        this.config = config;

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newHttpClient());
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(
                config.getInt(YoutubeConfigKeys.REQUEST_TIMEOUT_SECONDS)));

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public List<YoutubeChannelData> fetchChannels(List<String> channelIds) {
        List<YoutubeChannelData> result = new ArrayList<>();
        for (List<String> batch : partition(channelIds)) {
            JsonNode response = call(QuotaCost.CHANNELS_LIST, false, uri -> uri
                    .path("/channels")
                    .queryParam("part", "snippet,statistics,contentDetails")
                    .queryParam("id", String.join(",", batch))
                    .queryParam("maxResults", BATCH_SIZE)
                    .build());
            for (JsonNode item : response.path("items")) {
                result.add(toChannel(item));
            }
        }
        return result;
    }

    @Override
    public List<YoutubeVideoData> fetchVideos(List<String> videoIds) {
        List<YoutubeVideoData> result = new ArrayList<>();
        for (List<String> batch : partition(videoIds)) {
            JsonNode response = call(QuotaCost.VIDEOS_LIST, false, uri -> uri
                    .path("/videos")
                    .queryParam("part", "snippet,statistics,contentDetails,paidProductPlacementDetails")
                    .queryParam("id", String.join(",", batch))
                    .queryParam("maxResults", BATCH_SIZE)
                    .build());
            for (JsonNode item : response.path("items")) {
                result.add(toVideo(item));
            }
        }
        return result;
    }

    @Override
    public YoutubePage<String> fetchUploadedVideoIds(String uploadsPlaylistId, String pageToken) {
        JsonNode response = call(QuotaCost.PLAYLIST_ITEMS, false, uri -> {
            uri.path("/playlistItems")
                    .queryParam("part", "contentDetails")
                    .queryParam("playlistId", uploadsPlaylistId)
                    .queryParam("maxResults", BATCH_SIZE);
            if (pageToken != null) {
                uri.queryParam("pageToken", pageToken);
            }
            return uri.build();
        });

        List<String> ids = new ArrayList<>();
        for (JsonNode item : response.path("items")) {
            String videoId = item.path("contentDetails").path("videoId").asString();
            if (!videoId.isBlank()) {
                ids.add(videoId);
            }
        }
        return new YoutubePage<>(ids, text(response, "nextPageToken"));
    }

    @Override
    public List<YoutubeVideoData> fetchPopularVideos(String videoCategoryId, int maxResults) {
        JsonNode response = call(QuotaCost.VIDEOS_LIST, false, uri -> {
            uri.path("/videos")
                    .queryParam("part", "snippet,statistics,contentDetails,paidProductPlacementDetails")
                    .queryParam("chart", "mostPopular")
                    .queryParam("regionCode", config.getString(YoutubeConfigKeys.REGION_CODE))
                    .queryParam("maxResults", Math.min(maxResults, BATCH_SIZE));
            if (videoCategoryId != null && !videoCategoryId.isBlank()) {
                uri.queryParam("videoCategoryId", videoCategoryId);
            }
            return uri.build();
        });

        List<YoutubeVideoData> result = new ArrayList<>();
        for (JsonNode item : response.path("items")) {
            result.add(toVideo(item));
        }
        return result;
    }

    @Override
    public YoutubePage<String> searchChannelIds(String query, String pageToken) {
        JsonNode response = call(QuotaCost.SEARCH, true, uri -> {
            uri.path("/search")
                    .queryParam("part", "snippet")
                    .queryParam("type", "channel")
                    .queryParam("q", query)
                    .queryParam("regionCode", config.getString(YoutubeConfigKeys.REGION_CODE))
                    .queryParam("relevanceLanguage",
                            config.getString(YoutubeConfigKeys.RELEVANCE_LANGUAGE))
                    .queryParam("maxResults", BATCH_SIZE);
            if (pageToken != null) {
                uri.queryParam("pageToken", pageToken);
            }
            return uri.build();
        });

        List<String> ids = new ArrayList<>();
        for (JsonNode item : response.path("items")) {
            String channelId = item.path("id").path("channelId").asString();
            if (!channelId.isBlank()) {
                ids.add(channelId);
            }
        }
        return new YoutubePage<>(ids, text(response, "nextPageToken"));
    }

    @Override
    public List<String> fetchFeaturedChannelIds(String channelId) {
        JsonNode response = call(QuotaCost.CHANNEL_SECTIONS, false, uri -> uri
                .path("/channelSections")
                .queryParam("part", "contentDetails")
                .queryParam("channelId", channelId)
                .build());

        List<String> ids = new ArrayList<>();
        for (JsonNode item : response.path("items")) {
            for (JsonNode channel : item.path("contentDetails").path("channels")) {
                String id = channel.asString();
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    @Override
    public List<YoutubeCommentData> fetchComments(String videoId, CommentOrder order,
                                                  int maxResults) {
        JsonNode response = call(QuotaCost.COMMENT_THREADS, false, uri -> uri
                .path("/commentThreads")
                .queryParam("part", "snippet")
                .queryParam("videoId", videoId)
                .queryParam("order", order.parameter())
                .queryParam("textFormat", "plainText")
                .queryParam("maxResults", Math.min(maxResults, BATCH_SIZE))
                .build());

        List<YoutubeCommentData> result = new ArrayList<>();
        for (JsonNode item : response.path("items")) {
            JsonNode top = item.path("snippet").path("topLevelComment").path("snippet");
            // 작성자 필드는 읽지 않는다 — 여기서 버려야 AI 전송 경로로 새지 않는다.
            result.add(new YoutubeCommentData(
                    top.path("textDisplay").asString(),
                    top.path("likeCount").asLong(0),
                    parseInstant(top.path("publishedAt").asString()),
                    order));
        }
        return result;
    }

    // ==================================================================
    // 내부
    // ==================================================================

    /**
     * 공통 호출 골격.
     *
     * <p>{@code 403 quotaExceeded} 는 우리 원장이 실제 소모를 따라잡지 못한 경우다(다른 경로에서
     * 같은 키를 썼거나 계산이 어긋났을 때). 이때도 흐름 제어 예외로 바꿔 배치가 커서를 저장하고
     * 정상 종료하게 한다 — 재시도해도 그날은 성공하지 않는다.
     */
    private JsonNode call(int units, boolean search, Function<UriBuilder, java.net.URI> uriSpec) {
        if (!properties.isConfigured()) {
            throw new BusinessException(ErrorCode.YOUTUBE_API_FAILED, "API 키가 설정되지 않았습니다");
        }
        quotaService.ensureAvailable(units, search);

        try {
            JsonNode response = restClient.get()
                    .uri(uri -> {
                        java.net.URI built = uriSpec.apply(uri);
                        return java.net.URI.create(built + (built.getQuery() == null ? "?" : "&")
                                + "key=" + properties.apiKey());
                    })
                    .retrieve()
                    .body(JsonNode.class);
            return response == null ? tools.jackson.databind.node.NullNode.getInstance() : response;

        } catch (org.springframework.web.client.HttpClientErrorException.Forbidden e) {
            if (e.getResponseBodyAsString().contains("quotaExceeded")) {
                log.info("YouTube 할당량 초과 응답 — 원장보다 실제 소모가 앞섰다");
                throw new BusinessException(ErrorCode.YOUTUBE_QUOTA_EXCEEDED, 0, 0);
            }
            log.error("YouTube API 접근 거부 — {}", e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.YOUTUBE_API_FAILED, e.getStatusCode().toString());

        } catch (RestClientException e) {
            log.error("YouTube API 호출 실패", e);
            throw new BusinessException(ErrorCode.YOUTUBE_API_FAILED, e.getMessage());

        } finally {
            // 성공·실패와 무관하게 기록한다. 실패한 호출도 할당량은 차감된다.
            quotaService.record(units, search);
        }
    }

    private YoutubeChannelData toChannel(JsonNode item) {
        JsonNode snippet = item.path("snippet");
        JsonNode statistics = item.path("statistics");
        return new YoutubeChannelData(
                item.path("id").asString(),
                snippet.path("title").asString(),
                snippet.path("description").asString(),
                snippet.path("customUrl").asString(),
                snippet.path("country").asString(null),
                item.path("contentDetails").path("relatedPlaylists").path("uploads").asString(),
                statistics.path("subscriberCount").asLong(0),
                statistics.path("viewCount").asLong(0),
                statistics.path("videoCount").asInt(0),
                statistics.path("hiddenSubscriberCount").asBoolean(false));
    }

    private YoutubeVideoData toVideo(JsonNode item) {
        JsonNode snippet = item.path("snippet");
        JsonNode statistics = item.path("statistics");
        // 댓글이 차단된 영상은 commentCount 자체가 응답에 없다 — 0 과 구분해야 한다.
        boolean commentsDisabled = statistics.path("commentCount").isMissingNode();

        return new YoutubeVideoData(
                item.path("id").asString(),
                snippet.path("channelId").asString(),
                snippet.path("title").asString(),
                snippet.path("description").asString(),
                parseInstant(snippet.path("publishedAt").asString()),
                IsoDuration.toSeconds(item.path("contentDetails").path("duration").asString()),
                statistics.path("viewCount").asLong(0),
                statistics.path("likeCount").asLong(0),
                statistics.path("commentCount").asLong(0),
                item.path("paidProductPlacementDetails").path("hasPaidProductPlacement")
                        .asBoolean(false),
                commentsDisabled,
                snippet.path("categoryId").asString(null));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    private static Instant parseInstant(String value) {
        return value == null || value.isBlank() ? Instant.EPOCH : Instant.parse(value);
    }

    private static List<List<String>> partition(List<String> ids) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += BATCH_SIZE) {
            batches.add(ids.subList(i, Math.min(i + BATCH_SIZE, ids.size())));
        }
        return batches;
    }
}
