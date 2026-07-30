package kr.suhsaechan.palim.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    @DisplayName("12자 미만은 거부하고 12자는 허용한다")
    void 길이_경계() {
        assertThatThrownBy(() -> PasswordPolicy.validate("11-char-pwd", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PASSWORD_TOO_SHORT);
        assertThatCode(() -> PasswordPolicy.validate("12-char-pass", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("아이디 포함은 대소문자 무시하고 거부한다")
    void 아이디_포함() {
        assertThatThrownBy(() -> PasswordPolicy.validate("my-Admin-passphrase", "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PASSWORD_CONTAINS_USERNAME);
    }

    @Test
    @DisplayName("같은 문자 4회 연속은 거부한다")
    void 연속_반복() {
        assertThatThrownBy(() -> PasswordPolicy.validate("passsshrase-long", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PASSWORD_REPEATED_CHARS);
    }

    @Test
    @DisplayName("조합 규칙은 강제하지 않는다 — 길이만 충족하면 소문자 문장도 허용된다")
    void 문장형_허용() {
        // NIST SP 800-63B: 조합 강제는 예측 가능한 변형만 유도한다. 길이가 강도를 만든다.
        assertThatCode(() -> PasswordPolicy.validate("correct horse battery", "admin"))
                .doesNotThrowAnyException();
    }
}
