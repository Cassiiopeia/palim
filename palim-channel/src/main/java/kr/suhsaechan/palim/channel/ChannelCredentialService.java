package kr.suhsaechan.palim.channel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채널 API 인증정보 서비스 (설계서 6.2).
 *
 * <p>평문은 이 서비스 경계에서만 오간다. 저장 시 암호화하고 조회 시 복호화하므로, 호출자는
 * 암호문을 다루지 않는다. 반대로 <b>암호문이 이 서비스 밖으로 나가지도 않는다.</b>
 */
@Service
@RequiredArgsConstructor
public class ChannelCredentialService {

    private final ChannelRepository channelRepository;
    private final ChannelCredentialRepository channelCredentialRepository;
    private final CredentialCipher credentialCipher;

    /**
     * 인증정보를 등록하거나 갱신한다. 입력은 평문이다.
     *
     * <p>발주자가 웹 관리자에서 키를 갱신해도 재배포가 필요하지 않다(F-09).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void put(ChannelCode channelCode, String credentialKey, String plainValue) {
        UUID channelId = channelIdOf(channelCode);
        String encrypted = credentialCipher.encrypt(plainValue);

        channelCredentialRepository.findByChannelIdAndCredentialKey(channelId, credentialKey)
                .ifPresentOrElse(
                        existing -> existing.updateEncryptedValue(encrypted),
                        () -> channelCredentialRepository.save(
                                ChannelCredential.of(channelId, credentialKey, encrypted)));
    }

    /** 평문을 반환한다. 없으면 빈 값이다. */
    @Transactional(readOnly = true)
    public Optional<String> find(ChannelCode channelCode, String credentialKey) {
        return channelCredentialRepository
                .findByChannelIdAndCredentialKey(channelIdOf(channelCode), credentialKey)
                .map(ChannelCredential::getEncryptedValue)
                .map(credentialCipher::decrypt);
    }

    /** 평문을 반환한다. 없으면 예외 — 어댑터가 필수 인증정보를 요구할 때 쓴다. */
    @Transactional(readOnly = true)
    public String get(ChannelCode channelCode, String credentialKey) {
        return find(channelCode, credentialKey)
                .orElseThrow(() -> NotFoundException.of(
                        "채널 인증정보", "%s / %s".formatted(channelCode, credentialKey)));
    }

    /** 해당 채널의 전체 인증정보를 평문 맵으로 반환한다. */
    @Transactional(readOnly = true)
    public Map<String, String> getAll(ChannelCode channelCode) {
        Map<String, String> result = new LinkedHashMap<>();
        channelCredentialRepository.findByChannelId(channelIdOf(channelCode))
                .forEach(credential -> result.put(
                        credential.getCredentialKey(),
                        credentialCipher.decrypt(credential.getEncryptedValue())));
        return result;
    }

    /** 등록된 인증정보 키 목록. 값은 노출하지 않으므로 화면 표시에 안전하다. */
    @Transactional(readOnly = true)
    public java.util.List<String> findKeys(ChannelCode channelCode) {
        return channelCredentialRepository.findByChannelId(channelIdOf(channelCode)).stream()
                .map(ChannelCredential::getCredentialKey)
                .toList();
    }

    private UUID channelIdOf(ChannelCode channelCode) {
        return channelRepository.findByCode(channelCode)
                .orElseThrow(() -> NotFoundException.of("채널", channelCode))
                .getId();
    }
}
