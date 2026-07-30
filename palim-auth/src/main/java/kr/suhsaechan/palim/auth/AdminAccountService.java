package kr.suhsaechan.palim.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 계정 서비스 (F-09).
 *
 * <p>평문 비밀번호는 이 서비스 경계에서만 오간다. 엔티티는 인코딩된 값만 갖는다.
 */
@Service
@RequiredArgsConstructor
public class AdminAccountService {

    private final AdminAccountRepository adminAccountRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 계정이 없으면 만든다. 부트스트랩에서 호출한다.
     *
     * <p>이미 있으면 비밀번호를 덮어쓰지 않는다. 환경변수를 그대로 둔 상태로 재기동할 때마다
     * 발주자가 화면에서 바꾼 비밀번호가 초기값으로 되돌아가면 안 된다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AdminAccount createIfAbsent(String username, String rawPassword) {
        return adminAccountRepository.findByUsername(username)
                .orElseGet(() -> adminAccountRepository.save(
                        AdminAccount.create(username, passwordEncoder.encode(rawPassword))));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changePassword(String username, String rawPassword) {
        getByUsername(username).changePassword(passwordEncoder.encode(rawPassword));
    }

    @Transactional(readOnly = true)
    public AdminAccount getByUsername(String username) {
        return adminAccountRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_ACCOUNT_NOT_FOUND, username));
    }

    @Transactional(readOnly = true)
    public Optional<AdminAccount> findByUsername(String username) {
        return adminAccountRepository.findByUsername(username);
    }

    @Transactional(readOnly = true)
    public boolean exists(String username) {
        return adminAccountRepository.existsByUsername(username);
    }

    // ------------------------------------------------------------------
    // 로그인 실패 잠금
    // ------------------------------------------------------------------

    /**
     * 로그인 실패를 기록한다.
     *
     * <p><b>없는 계정이면 아무 것도 하지 않고 {@code false} 를 반환한다.</b> 없는 아이디에
     * 잠금을 걸 대상이 없기 때문이며, 응답 차이로 아이디 존재 여부가 새어나가지 않게 하는 것은
     * 호출부의 책임이다 — 이 메서드는 어느 경우에도 같은 타입을 반환한다.
     *
     * @return 이 호출로 계정이 잠겼으면 true
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordLoginFailure(String username, int maxFailure, Duration lockDuration) {
        return adminAccountRepository.findByUsername(username)
                .map(account -> account.recordLoginFailure(Instant.now(), maxFailure, lockDuration))
                .orElse(false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordLoginSuccess(String username, String clientIp) {
        getByUsername(username).recordLoginSuccess(Instant.now(), clientIp);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void unlock(String username) {
        getByUsername(username).unlock();
    }

    /**
     * 잠긴 계정인지.
     *
     * <p>없는 계정은 {@code false} 다. 잠금 여부로 아이디 존재를 알려주지 않기 위해, 호출부는
     * 이 값이 {@code true} 여도 사용자에게는 일반 로그인 실패와 같은 메시지를 보여야 한다.
     */
    @Transactional(readOnly = true)
    public boolean isLocked(String username) {
        return adminAccountRepository.findByUsername(username)
                .map(account -> account.isLocked(Instant.now()))
                .orElse(false);
    }
}
