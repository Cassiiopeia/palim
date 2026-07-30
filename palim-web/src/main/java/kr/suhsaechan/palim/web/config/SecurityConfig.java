package kr.suhsaechan.palim.web.config;

import java.time.Duration;
import kr.suhsaechan.palim.web.audit.WebAuditRecorder;
import kr.suhsaechan.palim.web.session.ActiveSessionRegistry;
import kr.suhsaechan.palim.web.session.AuditLogoutHandler;
import kr.suhsaechan.palim.web.session.DuplicateSessionExpiredStrategy;
import kr.suhsaechan.palim.web.session.LoginAttemptService;
import kr.suhsaechan.palim.web.session.LoginFailureHandler;
import kr.suhsaechan.palim.web.session.LoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * 관리자 화면 접근 제어와 보안 헤더.
 *
 * <p>관리자 계정 1개로 로그인한다. 다중 사용자·권한 분리는 범위에 포함하지 않는다(F-09).
 *
 * <h2>왜 이렇게까지 조이는가</h2>
 *
 * <p>이 화면은 <b>재고 수정과 채널 인증정보 등록</b>을 수행한다. 세션이 탈취되면 재고를 조작해
 * 오버셀링을 유발하거나 채널 API 키를 갈아치울 수 있고, 둘 다 매출에 직접 영향을 준다.
 *
 * <p>Cloudflare Tunnel 로 외부에 노출되므로 인터넷에서 접근 가능한 화면이라는 점을 전제한다.
 */
@Configuration
public class SecurityConfig {

    /**
     * 세션 만료 처리를 위한 레지스트리.
     *
     * <p>{@code SessionInformation.expireNow()} 로 다른 세션을 끊기 위해 필요하다. 동시 세션
     * <b>제한</b>은 이것에 맡기지 않는다 — 아래 {@code maximumSessions(-1)} 참고.
     */
    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * 세션 생성·소멸 이벤트를 Spring 에 전달한다.
     *
     * <p>이게 없으면 {@code SessionRegistryImpl} 에서 끝난 세션이 지워지지 않아, 로그아웃한 뒤에도
     * <b>접속 중으로 남아 중복 로그인으로 판정된다.</b>
     */
    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * 인증 상태 저장소.
     *
     * <p>중복 로그인 확인 후 직접 인증을 세우는 경로가 있어({@code DuplicateLoginController})
     * 같은 저장소를 명시적으로 공유해야 한다. 기본값에 의존하면 필터가 쓰는 저장소와 컨트롤러가
     * 쓰는 저장소가 어긋나 로그인 직후 다시 로그인 화면으로 튕긴다.
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new HttpSessionSecurityContextRepository(),
                new RequestAttributeSecurityContextRepository());
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SessionRegistry sessionRegistry,
            SecurityContextRepository securityContextRepository,
            ActiveSessionRegistry activeSessionRegistry,
            LoginAttemptService loginAttemptService,
            WebAuditRecorder webAuditRecorder) throws Exception {

        return http
                .authorizeHttpRequests(auth -> auth
                        // 자기 감시가 이 엔드포인트를 폴링해 장애를 텔레그램으로 통보한다(06-OPERATIONS).
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/favicon.ico").permitAll()
                        // 중복 로그인 확인은 아직 인증되지 않은 상태에서 거친다.
                        .requestMatchers("/login", "/login/duplicate", "/login/duplicate/cancel")
                        .permitAll()
                        .anyRequest().authenticated())

                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository))

                .formLogin(form -> form
                        .loginPage("/login")
                        // 기존 접속이 있으면 확인 화면으로 보내야 하므로 성공 URL 을 고정할 수 없다.
                        .successHandler(new LoginSuccessHandler(
                                activeSessionRegistry, loginAttemptService, webAuditRecorder))
                        .failureHandler(new LoginFailureHandler(loginAttemptService, webAuditRecorder))
                        .permitAll())

                .logout(logout -> logout
                        // 세션 무효화 전에 실행된다. 성공 핸들러에서는 누가 로그아웃했는지 알 수 없다.
                        .addLogoutHandler(new AuditLogoutHandler(webAuditRecorder))
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))

                // CSRF 는 기본 활성이며 끄지 않는다. 상태 변경은 전부 POST 다.
                .csrf(Customizer.withDefaults())

                .sessionManagement(session -> session
                        // 로그인 시 세션 ID 를 교체한다. 공격자가 미리 심어둔 세션으로
                        // 로그인 후 권한을 물려받는 고정 공격을 막는다.
                        .sessionFixation(fixation -> fixation.migrateSession())
                        // 유휴 만료는 server.servlet.session.timeout 으로 둔다.
                        .sessionConcurrency(concurrency -> concurrency
                                // -1 은 "제한하지 않는다" 가 아니라 "Spring 에게 맡기지 않는다" 다.
                                // 같은 계정 1세션 규칙은 LoginSuccessHandler 가 판정한다. Spring 에
                                // 맡기면 기존 접속자에게 아무 것도 묻지 않고 세션을 끊어버려,
                                // 발주자가 자기 계정이 다른 곳에서 쓰이는 것을 알아챌 수 없다.
                                .maximumSessions(-1)
                                .sessionRegistry(sessionRegistry)
                                .expiredSessionStrategy(new DuplicateSessionExpiredStrategy())))

                .headers(headers -> headers
                        // 클릭재킹 차단. 이 화면이 다른 사이트에 iframe 으로 박히면 안 된다.
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .xssProtection(xss -> xss
                                .headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        // Cloudflare Tunnel 이 HTTPS 종단이다.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(Duration.ofDays(365).toSeconds()))
                        // 스타일·스크립트를 자기 도메인으로 제한한다. CDN 을 쓰지 않고 빌드
                        // 산출물을 jar 에 포함하는 이유가 이 정책을 유지하기 위함이다(07-DECISIONS 009).
                        // connect-src 는 default-src 로 커버된다 — 세션 감시 SSE 가 같은 도메인이다.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; "
                                        + "style-src 'self'; "
                                        + "script-src 'self'; "
                                        + "img-src 'self' data:; "
                                        + "font-src 'self'; "
                                        + "form-action 'self'; "
                                        + "frame-ancestors 'none'; "
                                        + "base-uri 'self'")))
                .build();
    }
}
