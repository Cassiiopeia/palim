package kr.suhsaechan.palim.auth;

import java.util.Locale;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;

/**
 * 비밀번호 정책 (09-SECURITY).
 *
 * <p>NIST SP 800-63B 방향을 따른다 — <b>복잡성 조합("대문자+숫자+특수문자 각 1개")을 강제하지
 * 않는다.</b> 조합 강제는 {@code P@ssw0rd1!} 같은 예측 가능한 변형을 유도할 뿐 실제 강도를
 * 높이지 못한다는 것이 반복 확인됐다. 길이가 강도를 만든다.
 *
 * <p>레거시 검증기의 11종 검사(키보드 배열 연속 등)에서 실효가 있는 것만 남겼다.
 *
 * <p><b>부트스트랩 초기 비밀번호에는 적용하지 않는다.</b> 환경변수 초기값이 정책 미달이라고
 * 기동을 막으면 발주자가 시스템에 영영 못 들어간다. 화면에서의 변경에만 적용한다.
 */
public final class PasswordPolicy {

    /** 최소 길이. 조합 강제 없이 길이로 강도를 확보한다. */
    public static final int MIN_LENGTH = 12;

    /** 같은 문자 연속 허용 상한. {@code aaaa} 부터 거부한다. */
    public static final int MAX_REPEAT = 4;

    private PasswordPolicy() {
    }

    /**
     * 정책 위반이면 {@link BusinessException} 을 던진다.
     *
     * @param rawPassword 평문 비밀번호. 이 메서드는 저장하지 않는다
     * @param username    포함 금지 검사 대상 아이디
     */
    public static void validate(String rawPassword, String username) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_SHORT, MIN_LENGTH);
        }
        if (username != null && !username.isBlank()
                && rawPassword.toLowerCase(Locale.ROOT).contains(username.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.PASSWORD_CONTAINS_USERNAME);
        }
        if (hasRepeatedRun(rawPassword)) {
            throw new BusinessException(ErrorCode.PASSWORD_REPEATED_CHARS, MAX_REPEAT);
        }
    }

    private static boolean hasRepeatedRun(String value) {
        int run = 1;
        for (int i = 1; i < value.length(); i++) {
            run = value.charAt(i) == value.charAt(i - 1) ? run + 1 : 1;
            if (run >= MAX_REPEAT) {
                return true;
            }
        }
        return false;
    }
}
