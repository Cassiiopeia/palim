package kr.suhsaechan.palim.automation.influencer.youtube;

import java.util.List;

/**
 * YouTube Data API 접근 경계.
 *
 * <p>인터페이스로 분리한 이유는 두 가지다. 외부 API 정책이 바뀔 때 파장을 구현체 한 곳에
 * 가두기 위해서이고, 통합 테스트가 실제 호출 없이 고정 응답으로 파이프라인 전체를 검증할 수
 * 있어야 하기 때문이다.
 *
 * <p>구현체는 호출 전 할당량을 확인하고 호출 후 소모를 기록한다. 할당량 초과는
 * {@code YOUTUBE_QUOTA_EXCEEDED} 로 던져지며, 이는 오류가 아니라 배치의 정상 종료 신호다.
 */
public interface YoutubeClient {

    /** 채널 통계. 최대 50개 배치 — 1 unit. */
    List<YoutubeChannelData> fetchChannels(List<String> channelIds);

    /** 영상 통계. 최대 50개 배치 — 1 unit. */
    List<YoutubeVideoData> fetchVideos(List<String> videoIds);

    /** 업로드 재생목록의 영상 ID 목록 — 1 unit. */
    YoutubePage<String> fetchUploadedVideoIds(String uploadsPlaylistId, String pageToken);

    /** 국내 인기 차트의 영상 — 1 unit. 저렴해서 매일 순회해도 부담이 없다. */
    List<YoutubeVideoData> fetchPopularVideos(String videoCategoryId, int maxResults);

    /** 키워드로 채널 검색 — <b>100 units</b>. 예산 관리 대상이다. */
    YoutubePage<String> searchChannelIds(String query, String pageToken);

    /** 채널이 추천 섹션에 걸어둔 다른 채널 ID — 1 unit. */
    List<String> fetchFeaturedChannelIds(String channelId);

    /** 영상 댓글 — 1 unit. 작성자 정보는 반환하지 않는다(개인정보 최소 수집). */
    List<YoutubeCommentData> fetchComments(String videoId, CommentOrder order, int maxResults);
}
