package kr.suhsaechan.palim.notification.secret;

import java.util.Optional;
import kr.suhsaechan.palim.common.crypto.SecretCipher;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 알림 비밀값 보관.
 *
 * <p>평문은 이 경계에서만 오간다. 넣을 때 잠그고 쓸 때 푸므로 부르는 쪽은 암호문을 다루지
 * 않고, 반대로 <b>암호문이 이 밖으로 나가지도 않는다.</b>
 *
 * <p><b>값을 화면에 돌려주는 길을 만들지 않는다.</b> 확인용으로 한 번만 보여주는 화면이 있으면
 * 그 화면이 곧 유출 경로가 된다. 화면은 「등록됨」 인지만 안다.
 *
 * <p>연동 쪽에 같은 일을 하는 것이 있지만 가져다 쓸 수 없다 — 이 모듈은 그 모듈을 보지 않고,
 * 보게 만들면 모듈 경계가 깨진다. 암호기는 공용({@link SecretCipher})이라 그냥 주입된다.
 */
@Service
@RequiredArgsConstructor
public class NotificationSecretService {

    /** 메일 서버 비밀번호. */
    public static final String SMTP_PASSWORD = "smtp.password";

    private final NotificationSecretRepository repository;
    private final SecretCipher cipher;

    /** 넣거나 바꾼다. 들어오는 값은 평문이다. */
    @Transactional
    public void put(String secretName, String plainValue) {
        if (!StringUtils.hasText(plainValue)) {
            // 빈 값을 넣으면 「등록됨」 으로 보이면서 실제로는 인증이 실패한다.
            // 그 상태는 화면만 봐서는 구분되지 않으므로 아예 받지 않는다.
            throw new IllegalArgumentException("빈 값은 저장하지 않습니다: " + secretName);
        }
        String encrypted = cipher.encrypt(plainValue.trim());
        repository.findBySecretName(secretName)
                .ifPresentOrElse(
                        existing -> existing.updateEncryptedValue(encrypted),
                        () -> repository.save(NotificationSecret.of(
                                TenantContext.current(), secretName, encrypted)));
    }

    /** 평문을 돌려준다. 없으면 빈 값. 발송하는 순간에만 부른다. */
    @Transactional(readOnly = true)
    public Optional<String> find(String secretName) {
        return repository.findBySecretName(secretName)
                .map(NotificationSecret::getEncryptedValue)
                .map(cipher::decrypt);
    }

    /** 넣어 두었는가. 화면은 이것만 안다. */
    @Transactional(readOnly = true)
    public boolean exists(String secretName) {
        return repository.existsBySecretName(secretName);
    }

    @Transactional
    public void delete(String secretName) {
        repository.deleteBySecretName(secretName);
    }
}
