package kr.suhsaechan.palim.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import kr.suhsaechan.palim.common.error.BusinessException;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 인증정보 암복호화 단위 테스트 (설계서 6.2).
 */
class CredentialCipherTest {

    private static final String MASTER_KEY = base64Of("palim-test-master-key-32bytes!!!");
    private static final String OTHER_KEY = base64Of("palim-other-master-key-32bytes!!");

    private final CredentialCipher cipher = new CredentialCipher(MASTER_KEY);

    private static String base64Of(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("암호화 후 복호화하면 원문과 같다")
    void 왕복하면_원문과_같다() {
        String plaintext = "coupang-secret-key-값";

        String encrypted = cipher.encrypt(plaintext);

        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);
    }

    /**
     * GCM 에서 같은 키로 nonce 를 재사용하면 평문을 복원할 수 있는 치명적 취약점이 생긴다.
     * nonce 가 매번 새로 생성되는지를 암호문 차이로 확인한다.
     */
    @Test
    @DisplayName("같은 평문을 두 번 암호화하면 암호문이 다르다")
    void nonce가_매번_새로_생성된다() {
        String plaintext = "same-value";

        String first = cipher.encrypt(plaintext);
        String second = cipher.encrypt(plaintext);

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(plaintext);
        assertThat(cipher.decrypt(second)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("다른 마스터키로는 복호화할 수 없다")
    void 다른_키로는_복호화되지_않는다() {
        String encrypted = cipher.encrypt("secret");
        CredentialCipher otherCipher = new CredentialCipher(OTHER_KEY);

        assertThatThrownBy(() -> otherCipher.decrypt(encrypted))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ChannelErrorCode.CREDENTIAL_DECRYPT_FAILED);
    }

    @Test
    @DisplayName("변조된 암호문은 복호화가 실패한다 — GCM 인증 태그가 검출한다")
    void 변조된_암호문은_거부된다() {
        String encrypted = cipher.encrypt("secret");
        byte[] raw = Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ChannelErrorCode.CREDENTIAL_DECRYPT_FAILED);
    }

    @Test
    @DisplayName("마스터키가 없으면 생성에 실패한다")
    void 마스터키가_없으면_실패한다() {
        assertThatThrownBy(() -> new CredentialCipher(null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CredentialCipher("  "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("마스터키 길이가 256비트가 아니면 실패한다")
    void 키_길이가_다르면_실패한다() {
        String shortKey = base64Of("too-short");

        assertThatThrownBy(() -> new CredentialCipher(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256비트");
    }

    @Test
    @DisplayName("암호문 형식이 아니면 복호화가 실패한다")
    void 잘못된_형식은_거부된다() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[8]);

        assertThatThrownBy(() -> cipher.decrypt(tooShort))
                .isInstanceOf(BusinessException.class);
    }
}
