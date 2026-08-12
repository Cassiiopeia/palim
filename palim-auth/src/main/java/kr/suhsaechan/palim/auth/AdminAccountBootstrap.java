package kr.suhsaechan.palim.auth;

import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 계정 초기화 (F-09 · #51).
 *
 * <p>계정이 없으면 만든다. <b>이미 있으면 비밀번호를 덮어쓰지 않는다.</b> 환경변수를 그대로 둔
 * 상태로 재기동할 때마다 발주자가 화면에서 바꾼 비밀번호가 초기값으로 되돌아가면 안 되기 때문이다.
 *
 * <h2>비밀번호 환경변수가 없으면 기본값으로 만든다</h2>
 *
 * <p>예전에는 기동을 실패시켰다. 로그인할 수 없는 상태로 뜨는 것을 막으려는 의도였으나, 새
 * 환경에 올릴 때마다 환경변수를 먼저 준비해야 해서 번거로웠다.
 *
 * <p>대신 <b>초기 비밀번호로 만든 계정은 변경 강제 플래그를 세운다.</b> 이 저장소는 PUBLIC 이고
 * 화면은 인터넷에 노출되므로 기본값은 비밀이 아니라 공개된 값이다 — 플래그 없이 기본 계정만
 * 만들면 배포 즉시 누구나 들어올 수 있다. 변경 전까지 다른 화면을 막는 것이 편의와 안전을
 * 동시에 만족하는 유일한 조합이며, 공유기·NAS 가 쓰는 방식이다.
 */
@Slf4j
@Component
@Order(30)
public class AdminAccountBootstrap implements ApplicationRunner {

    /**
     * 환경변수가 없을 때 쓰는 초기 비밀번호.
     *
     * <p>비밀이 아니다 — 최초 로그인에서 반드시 바뀌는 값이라 공개되어도 무방하며, 오히려
     * 알려져 있어야 발주자가 처음 들어올 수 있다.
     */
    private static final String DEFAULT_PASSWORD = "admin";

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

        boolean useDefault = rawPassword == null || rawPassword.isBlank();
        String password = useDefault ? DEFAULT_PASSWORD : rawPassword;

        // 초기 비밀번호로 만든 계정만 변경을 강제한다. 환경변수로 지정한 비밀번호는
        // 발주자가 정한 값이므로 바꾸라고 할 이유가 없다.
        adminAccountService.createIfAbsent(username, password, useDefault);

        if (useDefault) {
            log.warn("관리자 계정을 초기 비밀번호로 생성했습니다 — {} / 최초 로그인 시 변경이 "
                    + "강제됩니다. 변경 전까지 다른 화면은 사용할 수 없습니다.", username);
        } else {
            log.info("관리자 계정 생성 — {}", username);
        }
    }
}
