package kr.suhsaechan.palim.connector.secret;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.suhsaechan.palim.common.crypto.SecretCipher;
import kr.suhsaechan.palim.connector.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 연동 인증정보 서비스.
 *
 * <p>평문은 이 서비스 경계에서만 오간다. 저장할 때 암호화하고 꺼낼 때 복호화하므로 호출자는
 * 암호문을 다루지 않고, 반대로 <b>암호문이 이 서비스 밖으로 나가지도 않는다.</b>
 *
 * <p>화면에는 {@link #keysOf} 로 <b>이름만</b> 내보낸다. 값은 한 번 저장하면 다시 보여주지
 * 않는다 — 확인용으로 한 번만 보여주는 화면이 있으면 그 화면이 곧 유출 경로가 된다.
 */
@Service
@RequiredArgsConstructor
public class ConnectorSecretService {

    private final ConnectorSecretRepository repository;
    private final SecretCipher cipher;

    /** 등록·갱신. 입력은 평문이다. */
    @Transactional
    public void put(String credentialRef, String secretName, String plainValue) {
        if (!StringUtils.hasText(plainValue)) {
            // 빈 값을 저장하면 "등록됨"으로 보이면서 실제로는 인증이 실패한다.
            // 그 상태는 화면만 봐서는 구분되지 않으므로 아예 받지 않는다.
            throw new IllegalArgumentException("빈 값은 저장하지 않습니다: " + secretName);
        }
        String encrypted = cipher.encrypt(plainValue.trim());
        repository.findByCredentialRefAndSecretName(credentialRef, secretName)
                .ifPresentOrElse(
                        existing -> existing.updateEncryptedValue(encrypted),
                        () -> repository.save(ConnectorSecret.of(
                                TenantContext.current(), credentialRef, secretName, encrypted)));
    }

    /** 평문을 돌려준다. 없으면 빈 값. */
    @Transactional(readOnly = true)
    public Optional<String> find(String credentialRef, String secretName) {
        return repository.findByCredentialRefAndSecretName(credentialRef, secretName)
                .map(ConnectorSecret::getEncryptedValue)
                .map(cipher::decrypt);
    }

    /** 등록된 값의 <b>이름만</b>. 화면 표시에 안전하다. */
    @Transactional(readOnly = true)
    public List<String> keysOf(String credentialRef) {
        return repository.findByCredentialRef(credentialRef).stream()
                .map(ConnectorSecret::getSecretName)
                .toList();
    }

    /** 연동을 지울 때 함께 지운다. 남겨두면 어디서도 참조하지 않는 비밀값이 쌓인다. */
    @Transactional
    public void deleteAll(String credentialRef) {
        repository.deleteByCredentialRef(credentialRef);
    }

    /** 커넥터마다 겹치지 않는 참조 이름. 커넥터 코드를 쓰면 사람이 읽을 수 있다. */
    public static String refOf(String connectorCode) {
        return "connector:" + connectorCode;
    }

    /** 아직 커넥터가 만들어지기 전(연결 테스트 단계)에 쓰는 임시 참조. */
    public static String draftRef(UUID draftId) {
        return "draft:" + draftId;
    }
}
