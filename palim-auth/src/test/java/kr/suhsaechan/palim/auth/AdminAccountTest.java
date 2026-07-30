package kr.suhsaechan.palim.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 로그인 실패 잠금 규칙 단위 테스트. Spring 컨텍스트를 띄우지 않는다(설계서 8장).
 */
class AdminAccountTest {

    private static final int MAX_FAILURE = 5;
    private static final Duration LOCK = Duration.ofMinutes(10);

    private static AdminAccount account() {
        return AdminAccount.create("admin", "{noop}encoded");
    }

    @Nested
    @DisplayName("잠금")
    class Lock {

        @Test
        void 임계치_미만_실패는_잠기지_않는다() {
            AdminAccount account = account();
            Instant now = Instant.now();

            for (int i = 0; i < MAX_FAILURE - 1; i++) {
                assertThat(account.recordLoginFailure(now, MAX_FAILURE, LOCK)).isFalse();
            }
            assertThat(account.isLocked(now)).isFalse();
        }

        @Test
        void 임계치_도달_시_잠기고_그_호출만_true_다() {
            AdminAccount account = account();
            Instant now = Instant.now();

            for (int i = 0; i < MAX_FAILURE - 1; i++) {
                account.recordLoginFailure(now, MAX_FAILURE, LOCK);
            }

            assertThat(account.recordLoginFailure(now, MAX_FAILURE, LOCK)).isTrue();
            assertThat(account.isLocked(now)).isTrue();
        }

        @Test
        void 이미_잠긴_상태의_실패는_잠금을_연장하지_않는다() {
            // 연장되면 공격자가 계속 시도하는 것만으로 정상 사용자를 영구히 잠글 수 있다.
            AdminAccount account = account();
            Instant now = Instant.now();

            for (int i = 0; i < MAX_FAILURE; i++) {
                account.recordLoginFailure(now, MAX_FAILURE, LOCK);
            }
            Instant lockedUntil = account.getLockedUntil();

            account.recordLoginFailure(now.plusSeconds(60), MAX_FAILURE, LOCK);

            assertThat(account.getLockedUntil()).isEqualTo(lockedUntil);
        }

        @Test
        void 잠금은_시간이_지나면_스스로_풀린다() {
            // 해제 배치가 없다. 배치가 멈추면 발주자가 자기 시스템에서 영구 잠기기 때문이다.
            AdminAccount account = account();
            Instant now = Instant.now();

            for (int i = 0; i < MAX_FAILURE; i++) {
                account.recordLoginFailure(now, MAX_FAILURE, LOCK);
            }

            assertThat(account.isLocked(now.plus(LOCK).plusSeconds(1))).isFalse();
        }
    }

    @Nested
    @DisplayName("해제")
    class Unlock {

        @Test
        void 성공하면_실패_횟수와_잠금이_함께_초기화된다() {
            AdminAccount account = account();
            Instant now = Instant.now();

            for (int i = 0; i < MAX_FAILURE; i++) {
                account.recordLoginFailure(now, MAX_FAILURE, LOCK);
            }

            account.recordLoginSuccess(now, "10.0.0.1");

            assertThat(account.isLocked(now)).isFalse();
            assertThat(account.getFailedLoginCount()).isZero();
            assertThat(account.getLastLoginIp()).isEqualTo("10.0.0.1");
        }

        @Test
        void 수동_해제도_실패_횟수를_초기화한다() {
            // 횟수를 남겨두면 해제 직후 실패 1번에 다시 잠긴다.
            AdminAccount account = account();
            Instant now = Instant.now();

            for (int i = 0; i < MAX_FAILURE; i++) {
                account.recordLoginFailure(now, MAX_FAILURE, LOCK);
            }

            account.unlock();

            assertThat(account.isLocked(now)).isFalse();
            assertThat(account.getFailedLoginCount()).isZero();
        }
    }
}
