package kr.suhsaechan.palim.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
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

    /**
     * 연속 로그인 실패 횟수.
     *
     * <p>성공하면 0 으로 초기화한다. 누적값이 아니라 <b>연속</b> 실패 횟수여야 한다 — 누적으로
     * 두면 몇 달 쓴 계정이 정상 사용 중에 잠긴다.
     */
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    /**
     * 잠금 해제 시각. {@code null} 이면 잠기지 않았다.
     *
     * <p>불린 {@code locked} 플래그를 두지 않는다. 플래그 방식은 해제 배치가 필요하고, 배치가
     * 멈추면 <b>발주자가 자기 시스템에서 영구 잠긴다.</b> 시각 비교는 배치 없이 스스로 풀린다.
     */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "last_login_ip", length = 45)
    private String lastLoginIp;

    @Version
    private Long version;

    private AdminAccount(String username, String passwordHash) {
        this.id = UuidV7.generate();
        this.username = username;
        this.passwordHash = passwordHash;
        this.enabled = true;
        this.failedLoginCount = 0;
    }

    public static AdminAccount create(String username, String encodedPassword) {
        if (username == null || username.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_USERNAME);
        }
        if (encodedPassword == null || encodedPassword.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }
        return new AdminAccount(username, encodedPassword);
    }

    public void changePassword(String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }
        this.passwordHash = encodedPassword;
    }

    public void disable() {
        this.enabled = false;
    }

    public void enable() {
        this.enabled = true;
    }

    // ------------------------------------------------------------------
    // 로그인 실패 잠금
    // ------------------------------------------------------------------

    /**
     * 지금 잠겨 있는지.
     *
     * <p>{@code lockedUntil} 이 과거면 잠금이 스스로 풀린 상태다. 값을 지우는 별도 처리 없이
     * 시각 비교만으로 판단한다.
     */
    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /**
     * 로그인 실패를 기록하고, 임계치에 도달하면 잠근다.
     *
     * @return 이 호출로 계정이 잠겼으면 true. 감사 로그에 {@code ACCOUNT_LOCKED} 를 남길지
     *         판단하는 데 쓴다 — 이미 잠긴 계정에 매 시도마다 잠금 기록을 남기면 안 된다.
     */
    public boolean recordLoginFailure(Instant now, int maxFailure, Duration lockDuration) {
        this.failedLoginCount++;

        if (failedLoginCount < maxFailure || isLocked(now)) {
            return false;
        }
        this.lockedUntil = now.plus(lockDuration);
        return true;
    }

    /**
     * 로그인 성공을 기록한다.
     *
     * <p>실패 횟수와 잠금을 함께 초기화한다. 실패 카운트만 지우고 {@code lockedUntil} 을 남기면
     * 성공한 로그인 뒤에도 잠김 판정이 계속 참이 된다.
     */
    public void recordLoginSuccess(Instant now, String clientIp) {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        this.lastLoginAt = now;
        this.lastLoginIp = clientIp;
    }

    /** 관리자가 직접 잠금을 푼다. */
    public void unlock() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }
}
