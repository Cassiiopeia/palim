package kr.suhsaechan.palim.automation.influencer.youtube;

import java.util.List;

/**
 * 페이지네이션 응답.
 *
 * @param nextPageToken 다음 페이지가 없으면 null. 발굴 커서에 그대로 저장한다
 */
public record YoutubePage<T>(List<T> items, String nextPageToken) {

    public boolean hasNext() {
        return nextPageToken != null && !nextPageToken.isBlank();
    }

    public static <T> YoutubePage<T> empty() {
        return new YoutubePage<>(List.of(), null);
    }
}
