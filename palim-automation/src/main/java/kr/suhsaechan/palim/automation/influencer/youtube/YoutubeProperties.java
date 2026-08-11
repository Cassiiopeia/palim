package kr.suhsaechan.palim.automation.influencer.youtube;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * YouTube API 접속 정보.
 *
 * <p>여기 있는 것은 <b>비밀값과 엔드포인트뿐</b>이다. 운영 중 조정하는 값(할당량·지역·임계값)은
 * {@code SystemConfig} 로 가야 화면에서 바꿀 수 있다. API 키는 저장소가 공개이므로 DB·파일에
 * 두지 않고 환경변수({@code YOUTUBE_API_KEY})로만 주입한다.
 *
 * @param apiKey  발급 시 사용량 하드 리밋을 걸어둔 키만 쓴다
 * @param baseUrl 테스트에서 스텁 서버로 바꾸기 위해 열어둔다
 */
@ConfigurationProperties(prefix = "palim.youtube")
public record YoutubeProperties(String apiKey, String baseUrl) {

    public YoutubeProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://www.googleapis.com/youtube/v3";
        }
    }

    /** 키가 없으면 수집 기능 전체가 동작하지 않는다. 기동은 되되 배치가 경고를 남기고 건너뛴다. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
