package kr.suhsaechan.palim;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각 공급자.
 *
 * <p>{@code Instant.now()} 를 코드에 직접 쓰면 "할당량이 자정에 초기화되는가", "하루 한 번만
 * 스냅샷을 남기는가" 같은 시간 의존 규칙을 테스트할 수 없다. 시각을 주입 대상으로 만들면
 * 고정 시계로 검증할 수 있다.
 *
 * <p>UTC 로 고정한다 — 전 계층이 {@code Instant} 이고 표시 직전에만 변환한다(CLAUDE.md).
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
