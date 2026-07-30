package kr.suhsaechan.palim.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 계정 초기화 (F-09).
 *
 * <p>계정이 없으면 환경변수 기반으로 만든다. <b>이미 있으면 비밀번호를 덮어쓰지 않는다.</b>
 * 환경변수를 그대로 둔 상태로 재기동할 때마다 발주자가 화면에서 바꾼 비밀번호가 초기값으로
 * 되돌아가면 안 되기 때문이다.
 *
 * <p>계정도 없고 비밀번호 환경변수도 없으면 <b>기동을 실패시킨다.</b> 로그인할 수 없는 상태로
 * 떠 있으면 관리자 화면 전체가 무용지물이므로, 조용히 넘어가는 것보다 즉시 드러내는 편이 낫다.
 */
@Slf4j
@Component
@Order(30)
public class AdminAccountBootstrap implements ApplicationRunner {

    private final AdminAccountService adminAccountService;
    private final String username;
    private final String rawPassword;

    public AdminAccountBootstrap(AdminAccountService adminAccountService,
                                 @Value("${palim.admin.username:admin}") String username,
                                 @Value("${palim.admin.password:}") String rawPassword) {
        this.adminAccountService = adminAccountService;
        this.username = username;
        this.rawPassword = rawPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminAccountService.exists(username)) {
            log.info("관리자 계정 확인 — {} (기존 계정 유지)", username);
            return;
        }

        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalStateException(
                    "관리자 계정 '%s' 이 없고 초기 비밀번호도 지정되지 않았습니다. "
                            .formatted(username)
                            + "환경변수 ADMIN_PASSWORD 를 지정하고 다시 기동하세요.");
        }

        adminAccountService.createIfAbsent(username, rawPassword);
        log.info("관리자 계정 생성 — {}", username);
    }
}
