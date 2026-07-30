package kr.suhsaechan.palim.web.session;

import kr.suhsaechan.palim.auth.AdminAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 시도 결과를 계정에 반영한다.
 *
 * <p>{@code AdminAccountService} 의 변경 메서드는 {@code Propagation.MANDATORY} 다. Spring
 * Security 의 핸들러는 트랜잭션 밖에서 호출되므로, 트랜잭션을 여는 조율 계층이 필요하다.
 * 핸들러에 직접 {@code @Transactional} 을 붙이면 프록시가 걸리지 않아 조용히 auto-commit 으로
 * 동작한다(CLAUDE.md 금지사항 2).
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final AdminAccountService adminAccountService;
    private final AuthPolicyProperties authPolicyProperties;

    /**
     * 실패를 기록한다.
     *
     * @return 이 실패로 계정이 잠겼으면 true
     */
    @Transactional
    public boolean recordFailure(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return adminAccountService.recordLoginFailure(username,
                authPolicyProperties.maxLoginFailure(), authPolicyProperties.lockDuration());
    }

    @Transactional
    public void recordSuccess(String username, String clientIp) {
        adminAccountService.recordLoginSuccess(username, clientIp);
    }
}
