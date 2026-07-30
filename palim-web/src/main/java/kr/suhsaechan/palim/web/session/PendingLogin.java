package kr.suhsaechan.palim.web.session;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

/**
 * 비밀번호 검증까지 끝났지만 중복 로그인 확인을 기다리는 상태.
 *
 * <h2>비밀번호도 {@code Authentication} 도 담지 않는다</h2>
 *
 * <p>확인 단계에서 다시 로그인시키려면 비밀번호가 필요할 것 같지만, 세션에 비밀번호를 보관하는
 * 순간 <b>메모리 덤프 하나로 평문이 새어나간다.</b> 검증은 이미 끝났으므로 아이디만 들고 있다가
 * 확인 시점에 {@code UserDetailsService} 로 계정을 다시 읽어 인증을 세운다.
 *
 * <p>{@code Authentication} 객체를 그대로 담지 않는 이유도 같다 — 인증된 객체를 세션에 두면
 * 그것 자체가 자격증명이 되고, 유효기간을 걸 지점이 사라진다.
 *
 * @param username         확인 후 로그인시킬 아이디
 * @param existingIp       기존 접속의 IP. 확인 화면에 보여준다
 * @param existingSessions 기존 접속 수
 * @param createdAt        보류 시작 시각
 */
public record PendingLogin(
        String username,
        String existingIp,
        int existingSessions,
        Instant createdAt
) implements Serializable {

    /** 세션 속성 키. */
    static final String SESSION_KEY = "palim.pendingLogin";

    /**
     * 유효시간이 지났는지.
     *
     * <p>보류 상태를 무기한 두면 세션 쿠키를 가진 누군가가 언제든 확인 버튼만 눌러 로그인할 수
     * 있다. 짧게 만료시킨다.
     */
    boolean isExpired(Instant now, Duration timeout) {
        return createdAt.plus(timeout).isBefore(now);
    }
}
