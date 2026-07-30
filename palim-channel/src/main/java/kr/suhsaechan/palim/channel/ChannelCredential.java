package kr.suhsaechan.palim.channel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채널 API 인증정보 (설계서 6.2).
 *
 * <p>채널마다 인증 필드 구성이 달라(쿠팡은 accessKey/secretKey/vendorId, 네이버는
 * clientId/clientSecret) 컬럼으로 고정하지 않고 key-value 로 저장한다.
 *
 * <p>값은 AES-GCM 으로 암호화해 보관하고 마스터키는 환경변수로 주입한다. 환경변수 단독 방식을
 * 쓰지 않는 이유는 웹 관리자에서 인증정보를 등록·갱신할 수 있어야 하기 때문이다(F-09).
 * 발주자가 키를 갱신해도 재배포가 필요하지 않다.
 */
@Getter
@Entity
@Table(
        name = "channel_credential",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_channel_credential_key",
                columnNames = {"channel_id", "credential_key"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChannelCredential extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(name = "credential_key", nullable = false, length = 50)
    private String credentialKey;

    /** AES-GCM 암호문. 평문을 이 필드에 넣어서는 안 된다. */
    @Column(nullable = false, length = 2000)
    private String encryptedValue;

    @Version
    private Long version;

    private ChannelCredential(UUID channelId, String credentialKey, String encryptedValue) {
        this.id = UuidV7.generate();
        this.channelId = channelId;
        this.credentialKey = credentialKey;
        this.encryptedValue = encryptedValue;
    }

    public static ChannelCredential of(UUID channelId, String credentialKey, String encryptedValue) {
        return new ChannelCredential(channelId, credentialKey, encryptedValue);
    }

    public void updateEncryptedValue(String encryptedValue) {
        this.encryptedValue = encryptedValue;
    }
}
