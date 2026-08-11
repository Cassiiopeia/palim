package kr.suhsaechan.palim.automation.influencer.ai;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 호출 쿨다운 캐시.
 *
 * <p>{@link ConcurrentMapCache} 로 충분하다. 담는 것은 캠페인 수만큼의 타임스탬프뿐이라
 * 메모리 압박이 없고, 만료가 필요 없다 — 값이 <b>시각</b>이라 오래된 항목은 비교에서 자연히
 * 통과되기 때문이다. TTL 캐시를 쓰면 오히려 쿨다운 설정을 바꿀 때 캐시를 다시 만들어야 한다.
 *
 * <p>다중 인스턴스로 가면 쿨다운이 인스턴스별로 따로 돌지만, <b>일일 상한은 DB 라 그대로
 * 유효하다</b> — 비용을 지키는 층은 무너지지 않는다.
 */
@Configuration
@EnableCaching
public class AiCacheConfiguration {

    @Bean
    public CacheManager aiCacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(java.util.List.of(
                new ConcurrentMapCache(AiCallGuard.COOLDOWN_CACHE,
                        new ConcurrentHashMap<>(), false)));
        return cacheManager;
    }
}
