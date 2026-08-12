package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.suhsaechan.palim.auth.AdminAccount;
import kr.suhsaechan.palim.auth.AdminAccountService;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 초기 비밀번호 변경 강제 검증 (#51).
 *
 * <p>기본 계정은 공개된 값으로 만들어지므로, 변경 강제가 동작하지 않으면 인터넷에 열린 화면이
 * 무방비가 된다.
 */
@Transactional
class PasswordChangeRequiredIntegrationTest extends IntegrationTest {

    @Autowired
    private AdminAccountService adminAccountService;

    @Test
    @DisplayName("초기 비밀번호로 만든 계정은 변경 강제 플래그를 갖는다")
    void 초기_비밀번호_계정() {
        AdminAccount account = adminAccountService.createIfAbsent("bootstrap-default", "admin", true);

        assertThat(account.isPasswordChangeRequired()).isTrue();
    }

    @Test
    @DisplayName("환경변수로 지정한 비밀번호로 만든 계정은 강제하지 않는다 — 발주자가 정한 값이다")
    void 지정_비밀번호_계정() {
        AdminAccount account = adminAccountService.createIfAbsent(
                "bootstrap-configured", "a-very-long-password", false);

        assertThat(account.isPasswordChangeRequired()).isFalse();
    }

    @Test
    @DisplayName("비밀번호를 바꾸면 강제 플래그가 함께 해제된다")
    void 변경시_플래그_해제() {
        adminAccountService.createIfAbsent("change-clears", "admin", true);

        adminAccountService.changePasswordVerified("change-clears", "admin", "충분히-긴-새-비밀번호-2026");

        assertThat(adminAccountService.getByUsername("change-clears").isPasswordChangeRequired())
                .isFalse();
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 강제 상태여도 바꿀 수 없다 — 세션 탈취 방어")
    void 현재_비밀번호_확인() {
        adminAccountService.createIfAbsent("verify-current", "admin", true);

        assertThatThrownBy(() -> adminAccountService.changePasswordVerified(
                "verify-current", "wrong", "충분히-긴-새-비밀번호-2026"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PASSWORD_MISMATCH);

        assertThat(adminAccountService.getByUsername("verify-current").isPasswordChangeRequired())
                .isTrue();
    }

    @Test
    @DisplayName("정책 미달 비밀번호로는 강제를 벗어날 수 없다")
    void 정책_적용() {
        adminAccountService.createIfAbsent("policy-applied", "admin", true);

        assertThatThrownBy(() -> adminAccountService.changePasswordVerified(
                "policy-applied", "admin", "short"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PASSWORD_TOO_SHORT);

        assertThat(adminAccountService.getByUsername("policy-applied").isPasswordChangeRequired())
                .isTrue();
    }
}
