package kr.suhsaechan.palim.automation.influencer.youtube;

/**
 * 채널 조회 결과.
 *
 * <p>API 응답 그대로가 아니라 우리가 쓰는 것만 담는다. 외부 스키마가 바뀌어도 파장이 이
 * 레코드와 매퍼에서 멈춘다.
 *
 * @param uploadsPlaylistId 최근 영상을 1 unit 으로 읽는 통로
 * @param country           비공개인 채널이 많아 null 이 흔하다
 */
public record YoutubeChannelData(
        String channelId,
        String title,
        String description,
        String handle,
        String country,
        String uploadsPlaylistId,
        long subscriberCount,
        long viewCount,
        int videoCount,
        boolean subscriberCountHidden) {
}
