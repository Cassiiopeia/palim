package kr.suhsaechan.palim.auth;

import java.util.Optional;
import kr.suhsaechan.palim.common.error.BusinessException;
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
                .orElseThrow(() -> new BusinessException(AuthErrorCode.ADMIN_ACCOUNT_NOT_FOUND, username));
    }

    @Transactional(readOnly = true)
    public Optional<AdminAccount> findByUsername(String username) {
        return adminAccountRepository.findByUsername(username);
    }

    @Transactional(readOnly = true)
    public boolean exists(String username) {
        return adminAccountRepository.existsByUsername(username);
    }
}
