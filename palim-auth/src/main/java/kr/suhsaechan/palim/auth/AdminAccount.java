package kr.suhsaechan.palim.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자 계정 (F-09).
 *
 * <p>관리자 계정 1개로 로그인한다. 다중 사용자·권한 분리는 개발 범위에 포함하지 않으므로
 * 역할(role) 컬럼을 두지 않는다.
 *
 * <p>비밀번호는 해시만 보관한다. 해싱 알고리즘은 {@code palim-web}이 주입하는
 * {@code PasswordEncoder}가 결정하며, 이 엔티티는 결과 문자열만 갖는다.
 */
@Getter
@Entity
@Table(name = "admin_account")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAccount extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** 인코딩된 비밀번호. 평문을 넣어서는 안 된다. */
    @Column(nullable = false, length = 200)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled;

    @Version
    private Long version;

    private AdminAccount(String username, String passwordHash) {
        this.id = UuidV7.generate();
        this.username = username;
        this.passwordHash = passwordHash;
        this.enabled = true;
    }

    public static AdminAccount create(String username, String encodedPassword) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("사용자명이 비어 있습니다");
        }
        if (encodedPassword == null || encodedPassword.isBlank()) {
            throw new IllegalArgumentException("인코딩된 비밀번호가 비어 있습니다");
        }
        return new AdminAccount(username, encodedPassword);
    }

    public void changePassword(String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isBlank()) {
            throw new IllegalArgumentException("인코딩된 비밀번호가 비어 있습니다");
        }
        this.passwordHash = encodedPassword;
    }

    public void disable() {
        this.enabled = false;
    }

    public void enable() {
        this.enabled = true;
    }
}
