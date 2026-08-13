package kr.suhsaechan.palim.connector.secret;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.connector.tenant.TenantFilters;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

/**
 * 연동 인증정보 (암호문).
 *
 * <p>커넥터가 {@code credentialRef} 로 이 묶음을 가리킨다. 커넥터 정의와 비밀값을 분리한 이유는
 * <b>정의는 보여줘도 되고 비밀값은 안 되기 때문</b>이다. 커넥터 목록·매핑 화면은 정의만 읽으므로
 * 실수로 비밀값이 함께 조회되는 경로가 생기지 않는다.
 *
 * <p>한 연동이 값을 여러 개 가질 수 있어({@code apiKey} 와 {@code password} 처럼)
 * {@code secretName} 로 구분한다.
 *
 * <p><b>평문은 이 엔티티에 들어오지 않는다.</b> 암호화는 서비스 경계에서 끝내고, 여기에는
 * 암호문만 담긴다.
 */
@Getter
@Entity
@Filter(name = TenantFilters.TENANT_FILTER, condition = TenantFilters.TENANT_CONDITION)
@Table(name = "connector_secret")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectorSecret extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    /** 커넥터가 가리키는 이름. 커넥터를 지워도 인증정보가 남아 재사용할 수 있다. */
    @Column(nullable = false, length = 100)
    private String credentialRef;

    /**
     * 값의 <b>이름표</b> — {@code apiKey}, {@code password} 등.
     *
     * <p>암호키가 아니다. 암호화에 쓰는 마스터키는 설정({@code palim.crypto.master-key})에
     * 있고 DB 에 저장되지 않는다.
     */
    @Column(nullable = false, length = 50)
    private String secretName;

    @Column(nullable = false, columnDefinition = "text")
    private String encryptedValue;

    private ConnectorSecret(UUID tenantId, String credentialRef, String secretName,
                            String encryptedValue) {
        this.id = UuidV7.generate();
        this.tenantId = tenantId;
        this.credentialRef = credentialRef;
        this.secretName = secretName;
        this.encryptedValue = encryptedValue;
    }

    public static ConnectorSecret of(UUID tenantId, String credentialRef, String secretName,
                                     String encryptedValue) {
        return new ConnectorSecret(tenantId, credentialRef, secretName, encryptedValue);
    }

    public void updateEncryptedValue(String encryptedValue) {
        this.encryptedValue = encryptedValue;
    }
}
