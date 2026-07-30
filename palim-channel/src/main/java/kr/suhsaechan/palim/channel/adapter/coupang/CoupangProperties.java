package kr.suhsaechan.palim.channel.adapter.coupang;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 쿠팡 어댑터 설정.
 *
 * <h2>{@code minRequestInterval} 이 가장 중요하다</h2>
 *
 * <p>쿠팡은 초당 10회 제한이며 <b>지속 초과 시 영구 차단</b>된다. 차단되면 발주자가 쿠팡에
 * 문의해야 복구되므로 실패 비용이 다른 채널과 다르다. 기본값을 규정(100ms)보다 넉넉하게
 * 잡은 이유다.
 *
 * @param apiBaseUrl         API 기본 주소
 * @param minRequestInterval 요청 간 최소 간격. 페이징 순회 중에도 지킨다
 * @param pageSize           한 페이지당 주문 수
 * @param maxPages           페이징 순회 상한. 무한 루프 방지용
 * @param timeout            호출 타임아웃
 */
@ConfigurationProperties(prefix = "palim.channel.coupang")
public record CoupangProperties(
        String apiBaseUrl,
        Duration minRequestInterval,
        int pageSize,
        int maxPages,
        Duration timeout
) {

    private static final String DEFAULT_API_BASE_URL = "https://api-gateway.coupang.com";

    /** 규정은 초당 10회(100ms)지만 영구 차단 위험이 있어 여유를 둔다. */
    private static final Duration DEFAULT_MIN_REQUEST_INTERVAL = Duration.ofMillis(150);

    private static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * 페이징 상한.
     *
     * <p>{@code nextToken} 이 예상과 다르게 동작하면 무한 루프가 되고, 그 상태로 호출 제한을
     * 초과하면 계정이 차단된다. 상한에 도달하면 경고를 남기고 멈춘다.
     */
    private static final int DEFAULT_MAX_PAGES = 100;

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    public CoupangProperties {
        apiBaseUrl = apiBaseUrl != null && !apiBaseUrl.isBlank() ? apiBaseUrl : DEFAULT_API_BASE_URL;
        minRequestInterval = minRequestInterval != null
                ? minRequestInterval : DEFAULT_MIN_REQUEST_INTERVAL;
        pageSize = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        maxPages = maxPages > 0 ? maxPages : DEFAULT_MAX_PAGES;
        timeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
    }
}
