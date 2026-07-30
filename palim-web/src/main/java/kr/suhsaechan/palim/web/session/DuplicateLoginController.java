package kr.suhsaechan.palim.web.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import kr.suhsaechan.palim.audit.AuditType;
import kr.suhsaechan.palim.web.audit.ClientIpResolver;
import kr.suhsaechan.palim.web.audit.WebAuditRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 중복 로그인 확인 (Redmine #208170 · #208171).
 *
 * <p>기존 접속이 있는 계정으로 로그인하면 여기로 온다. 확인하면 기존 접속을 끊고 로그인하고,
 * 취소하면 로그인하지 않는다.
 *
 * <h2>확인 시점에 비밀번호를 다시 받지 않는다</h2>
 *
 * <p>검증은 직전 단계에서 끝났다. 비밀번호를 세션이나 폼 hidden 에 들고 있다가 재사용하는 방식은
 * <b>평문을 한 번 더 노출시키는 것</b>이므로 쓰지 않는다. 아이디로 계정을 다시 읽어 인증을 세운다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class DuplicateLoginController {

    private final SessionRegistry sessionRegistry;
    private final ActiveSessionRegistry activeSessionRegistry;
    private final SessionWatchRegistry sessionWatchRegistry;
    private final LoginAttemptService loginAttemptService;
    private final WebAuditRecorder webAuditRecorder;
    private final UserDetailsService userDetailsService;
    private final SecurityContextRepository securityContextRepository;
    private final AuthPolicyProperties authPolicyProperties;

    @GetMapping("/login/duplicate")
    public String confirmPage(HttpServletRequest request, Model model) {
        PendingLogin pending = readPending(request);
        if (pending == null) {
            return "redirect:/login";
        }

        model.addAttribute("title", "중복 로그인");
        model.addAttribute("existingIp", pending.existingIp());
        model.addAttribute("existingSessions", pending.existingSessions());
        return "login/duplicate";
    }

    /**
     * 기존 접속을 종료하고 로그인한다.
     *
     * <p>감사 로그에 두 줄이 남는다 — 강제 종료된 기존 접속({@code LOGOUT_DUPLICATE})과 새
     * 로그인({@code LOGIN_SUCCESS}). 보류 시점의 {@code LOGIN_BLOCKED_DUPLICATE} 까지 합쳐
     * 세 줄로 전체 흐름이 재구성된다.
     *
     * <p><b>보류 경합</b> — 두 브라우저가 동시에 보류 상태에서 둘 다 확인을 누를 수 있다.
     * 잠금으로 막지 않는다: 기존 세션 조회({@code expireExistingSessions})가 확인 시점에 다시
     * 일어나므로, 나중에 누른 쪽이 먼저 누른 쪽의 새 세션을 끊는다. 최종 상태는 어느 경우든
     * "마지막 확인자 1세션"이며, 이는 중복 로그인 정책이 의도하는 결과와 같다.
     */
    @PostMapping("/login/duplicate")
    public String confirm(HttpServletRequest request, HttpServletResponse response) {
        PendingLogin pending = readPending(request);
        if (pending == null) {
            return "redirect:/login?expired";
        }

        UserDetails user;
        try {
            user = userDetailsService.loadUserByUsername(pending.username());
        } catch (UsernameNotFoundException exception) {
            // 보류 중에 계정이 사라졌다.
            clearPending(request);
            return "redirect:/login?error";
        }

        // 보류 중에 계정이 잠기거나 비활성화됐을 수 있다. 여기서 다시 확인하지 않으면
        // 비밀번호 검증 이후의 상태 변화를 무시하고 들어오게 된다.
        if (!user.isEnabled() || !user.isAccountNonLocked()) {
            clearPending(request);
            webAuditRecorder.recordAuth(AuditType.LOGIN_BLOCKED_LOCKED, pending.username(), request);
            return "redirect:/login?error";
        }

        expireExistingSessions(user, request);

        String clientIp = ClientIpResolver.resolve(request);
        HttpSession fresh = rotateSession(request);

        authenticate(user, request, response);

        loginAttemptService.recordSuccess(pending.username(), clientIp);
        sessionRegistry.registerNewSession(fresh.getId(), user);
        activeSessionRegistry.register(fresh.getId(), pending.username(), clientIp);
        webAuditRecorder.recordAuth(AuditType.LOGIN_SUCCESS, pending.username(), request);
        log.info("중복 로그인 확인 후 로그인 — {} {}", pending.username(), clientIp);

        return "redirect:/";
    }

    /** 로그인하지 않고 돌아간다. 기존 접속은 그대로 유지된다. */
    @PostMapping("/login/duplicate/cancel")
    public String cancel(HttpServletRequest request) {
        clearPending(request);
        return "redirect:/login?cancelled";
    }

    // ------------------------------------------------------------------

    /**
     * 같은 계정의 기존 세션을 만료시킨다.
     *
     * <p>{@code expireNow()} 는 즉시 끊는 게 아니라 만료 표시만 한다. 실제 무효화는 그 세션의
     * 다음 요청에서 {@code ConcurrentSessionFilter} 가 수행한다. 브라우저가 유휴 상태면 사용자는
     * 아무 것도 모른 채 화면을 보고 있게 되므로, SSE 로 즉시 통보해 화면을 정리시킨다.
     *
     * <p>조회 principal 은 <b>{@code UserDetails} 여야 한다.</b> {@code SessionRegistryImpl} 은
     * 로그인 시점의 principal 객체를 맵 키로 쓰는데, Spring 의 {@code User} 는 같은 아이디의
     * {@code User} 끼리만 같다. 문자열 아이디로 조회하면 항상 빈 목록이 나와 <b>기존 세션이
     * 하나도 끊기지 않는다.</b>
     */
    private void expireExistingSessions(UserDetails user, HttpServletRequest request) {
        String username = user.getUsername();
        List<SessionInformation> existing = sessionRegistry.getAllSessions(user, false);

        for (SessionInformation info : existing) {
            String sessionId = info.getSessionId();
            String ip = activeSessionRegistry.find(sessionId)
                    .map(ActiveSession::clientIp)
                    .orElse(ClientIpResolver.UNKNOWN);

            info.expireNow();
            sessionWatchRegistry.notifyDuplicateLogin(sessionId);
            activeSessionRegistry.remove(sessionId);

            webAuditRecorder.recordAuth(AuditType.LOGOUT_DUPLICATE, username, request,
                    "로그아웃했습니다.(로그인 중복 — 접속 %s 강제 종료)".formatted(ip));
            log.info("중복 로그인으로 기존 세션 종료 — {} {}", username, ip);
        }
    }

    /**
     * 세션을 교체한다.
     *
     * <p>인증이 확정되는 지점이므로 세션 ID 를 바꾼다. 보류 단계에서 이미 한 번 새 세션을
     * 만들었지만, 그 세션 ID 는 확인 화면을 거치며 노출됐을 수 있다.
     */
    private HttpSession rotateSession(HttpServletRequest request) {
        HttpSession pendingSession = request.getSession(false);
        if (pendingSession != null) {
            pendingSession.invalidate();
        }
        return request.getSession(true);
    }

    private void authenticate(UserDetails user, HttpServletRequest request,
                              HttpServletResponse response) {
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // 새 세션에 저장한다. 이 호출을 빠뜨리면 리다이렉트 직후 다시 로그인 화면으로 튕긴다.
        securityContextRepository.saveContext(context, request, response);
    }

    /** 보류 정보. 없거나 만료됐으면 {@code null} 이며 세션에서 지운다. */
    private PendingLogin readPending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        if (!(session.getAttribute(PendingLogin.SESSION_KEY) instanceof PendingLogin pending)) {
            return null;
        }
        if (pending.isExpired(Instant.now(), authPolicyProperties.pendingLoginTimeout())) {
            session.removeAttribute(PendingLogin.SESSION_KEY);
            return null;
        }
        return pending;
    }

    private void clearPending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
