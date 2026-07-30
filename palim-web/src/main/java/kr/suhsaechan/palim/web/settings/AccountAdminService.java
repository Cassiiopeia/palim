package kr.suhsaechan.palim.web.settings;

import kr.suhsaechan.palim.audit.AuditType;
import kr.suhsaechan.palim.auth.AdminAccountService;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.web.audit.WebAuditRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 설정 화면용 조율 서비스.
 *
 * <p>{@code AdminAccountService.changePasswordVerified} 가 {@code MANDATORY} 이므로 트랜잭션을
 * 여는 계층이 필요하다.
 *
 * <h2>실패도 감사 기록 대상이다</h2>
 *
 * <p>비밀번호 변경 실패가 반복되면 세션을 잡은 공격자가 현재 비밀번호를 대입하고 있다는
 * 신호다. 성공만 기록하면 이 신호를 잃는다. 감사 기록은 별도 트랜잭션이라 실패로 본 트랜잭션이
 * 롤백돼도 남는다.
 */
@Service
@RequiredArgsConstructor
public class AccountAdminService {

    private final AdminAccountService adminAccountService;
    private final WebAuditRecorder webAuditRecorder;

    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        try {
            adminAccountService.changePasswordVerified(username, currentPassword, newPassword);
        } catch (BusinessException exception) {
            webAuditRecorder.recordChange(AuditType.PASSWORD_CHANGE_FAILED,
                    "비밀번호 변경에 실패했습니다. (%s)".formatted(exception.getErrorCode().name()),
                    null, null);
            throw exception;
        }
        webAuditRecorder.recordChange(AuditType.PASSWORD_CHANGE, null, null, null, null, null);
    }
}
