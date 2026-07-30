package kr.suhsaechan.palim.channel;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 채널 API 인증정보 암복호화 (설계서 6.2).
 *
 * <p>AES-GCM 을 쓴다. 인증 태그가 포함되어 있어 암호문이 변조되면 복호화가 실패하므로,
 * 데이터베이스가 유출된 뒤 값이 조작되는 상황도 감지된다.
 *
 * <p>저장 형식은 {@code base64(nonce || ciphertext || tag)} 다. nonce 는 암호화마다 새로
 * 생성한다. GCM 에서 같은 키로 nonce 를 재사용하면 평문을 복원할 수 있는 치명적 취약점이
 * 생기므로, 고정 nonce 를 쓰면 안 된다.
 *
 * <p>마스터키가 없으면 <b>기동을 실패시킨다.</b> 암호화 없이 인증정보를 저장하는 상태로 뜨는
 * 것보다 뜨지 않는 편이 안전하다.
 *
 * <p>키 생성 — {@code openssl rand -base64 32}
 */
@Component
public class CredentialCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey masterKey;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(@Value("${palim.crypto.master-key}") String base64MasterKey) {
        byte[] keyBytes = decodeKey(base64MasterKey);
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "마스터키는 256비트(32바이트)여야 합니다. 현재 %d바이트. openssl rand -base64 32 로 생성하세요."
                            .formatted(keyBytes.length));
        }
        this.masterKey = new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }

    /** 평문을 암호화해 저장 형식 문자열로 반환한다. */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("암호화할 평문이 없습니다");
        }
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new CredentialCipherException("인증정보 암호화에 실패했습니다", e);
        }
    }

    /** 저장 형식 문자열을 복호화한다. 변조되었거나 다른 키로 암호화된 값이면 실패한다. */
    public String decrypt(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("복호화할 암호문이 없습니다");
        }
        byte[] combined = Base64.getDecoder().decode(encoded);
        if (combined.length <= NONCE_LENGTH_BYTES) {
            throw new CredentialCipherException("암호문 형식이 올바르지 않습니다", null);
        }
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        byte[] ciphertext = new byte[combined.length - NONCE_LENGTH_BYTES];
        System.arraycopy(combined, 0, nonce, 0, NONCE_LENGTH_BYTES);
        System.arraycopy(combined, NONCE_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new CredentialCipherException(
                    "인증정보 복호화에 실패했습니다. 마스터키가 변경되었거나 암호문이 손상되었습니다", e);
        }
    }

    private static byte[] decodeKey(String base64MasterKey) {
        if (base64MasterKey == null || base64MasterKey.isBlank()) {
            throw new IllegalStateException(
                    "마스터키가 설정되지 않았습니다. 환경변수 PALIM_CRYPTO_MASTER_KEY 를 지정하세요.");
        }
        try {
            return Base64.getDecoder().decode(base64MasterKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("마스터키가 Base64 형식이 아닙니다", e);
        }
    }
}
