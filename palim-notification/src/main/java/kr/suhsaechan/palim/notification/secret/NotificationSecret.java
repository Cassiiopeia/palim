package kr.suhsaechan.palim.notification.secret;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.common.UuidV7;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림이 쓰는 비밀값.
 *
 * <p>담기는 것은 <b>암호문뿐</b>이다. 암호를 푸는 열쇠는 설정에 있고 DB 에 들어가지 않는다 —
 * 둘이 같은 곳에 있으면 한 번의 유출로 둘 다 잃는다.
 *
 * <p>발송 설정과 <b>표를 나눈 이유</b> — 설정은 화면이 통째로 읽어 그리는 것이라, 비밀번호가
 * 그 안에 있으면 화면·감사 기록·직렬화로 새는 길이 한꺼번에 열린다.
 */
@Entity
@Getter
@Table(name = "notification_secret")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSecret extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    /** 값의 이름표({@code smtp.password}). 암호를 푸는 열쇠가 아니다. */
    @Column(nullable = false, length = 50)
    private String secretName;

    /** base64(nonce || ciphertext || tag). AES-256-GCM. */
    @Column(nullable = false, columnDefinition = "text")
    private String encryptedValue;

    private NotificationSecret(UUID tenantId, String secretName, String encryptedValue) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.secretName = secretName;
        this.encryptedValue = encryptedValue;
    }

    public static NotificationSecret of(UUID tenantId, String secretName, String encryptedValue) {
        return new NotificationSecret(tenantId, secretName, encryptedValue);
    }

    public void updateEncryptedValue(String encryptedValue) {
        this.encryptedValue = encryptedValue;
    }
}
