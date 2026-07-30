package kr.suhsaechan.palim.web.security;

import kr.suhsaechan.palim.auth.AdminAccount;
import kr.suhsaechan.palim.auth.AdminAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 관리자 계정을 Spring Security 에 연결한다.
 *
 * <p>권한은 {@code ROLE_ADMIN} 하나뿐이다. 관리자 계정 1개로 운영하므로 권한 분리가 없다(F-09).
 */
@Service
@RequiredArgsConstructor
public class AdminUserDetailsService implements UserDetailsService {

    @SuppressWarnings("unused")
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final AdminAccountService adminAccountService;

    @Override
    public UserDetails loadUserByUsername(String username) {
        AdminAccount account = adminAccountService.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("계정을 찾을 수 없습니다: " + username));

        return User.withUsername(account.getUsername())
                .password(account.getPasswordHash())
                .disabled(!account.isEnabled())
                .authorities(ROLE_ADMIN)
                .build();
    }
}
