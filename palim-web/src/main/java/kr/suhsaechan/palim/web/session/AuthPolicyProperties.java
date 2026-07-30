package kr.suhsaechan.palim.web.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로그인 보안 정책.
 *
 * @param maxLoginFailure     연속 로그인 실패 임계치. 도달하면 계정을 잠근다
 * @param lockDuration        잠금 유지 시간
 * @param pendingLoginTimeout 중복 로그인 확인 대기 유효시간
 */
@ConfigurationProperties(prefix = "palim.auth")
public record AuthPolicyProperties(
        int maxLoginFailure,
        Duration lockDuration,
        Duration pendingLoginTimeout
) {

    private static final int DEFAULT_MAX_LOGIN_FAILURE = 5;
    private static final Duration DEFAULT_LOCK_DURATION = Duration.ofMinutes(10);
    private static final Duration DEFAULT_PENDING_LOGIN_TIMEOUT = Duration.ofMinutes(2);

    public AuthPolicyProperties {
        // 0 이나 음수는 "잠금 없음" 이 아니라 설정 실수다. 잠금이 꺼진 줄 모르고 운영하는
        // 상황을 만들지 않기 위해 기본값으로 되돌린다.
        maxLoginFailure = maxLoginFailure > 0 ? maxLoginFailure : DEFAULT_MAX_LOGIN_FAILURE;
        lockDuration = lockDuration != null ? lockDuration : DEFAULT_LOCK_DURATION;
        pendingLoginTimeout = pendingLoginTimeout != null
                ? pendingLoginTimeout : DEFAULT_PENDING_LOGIN_TIMEOUT;
    }
}
