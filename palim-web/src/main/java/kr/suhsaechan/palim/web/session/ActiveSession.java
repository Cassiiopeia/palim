package kr.suhsaechan.palim.web.session;

import java.time.Instant;

/**
 * 접속 중인 세션 1건.
 *
 * <p>Spring Security 의 {@code SessionInformation} 은 세션 ID · principal · 최근 요청 시각만
 * 갖고 <b>IP 를 갖지 않는다.</b> 중복 로그인 확인 화면은
 * "기존 접속을 종료하고 새로 로그인하시겠습니까? IP: x.x.x.x" 를 보여줘야 하므로 IP 가 필요하고,
 * 강제 종료 감사 기록에도 어느 IP 의 접속이 끊겼는지 남아야 한다.
 */
public record ActiveSession(
        String sessionId,
        String username,
        String clientIp,
        Instant loggedInAt
) {
}
