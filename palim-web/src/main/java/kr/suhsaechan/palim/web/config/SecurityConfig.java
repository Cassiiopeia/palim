package kr.suhsaechan.palim.web.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.SessionFixationProtectionStrategy;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;

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

    /** 유휴 세션 만료. 관리자가 화면을 열어둔 채 자리를 떠도 무한정 유지되지 않게 한다. */
    private static final Duration SESSION_TIMEOUT = Duration.ofHours(2);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        // 자기 감시가 이 엔드포인트를 폴링해 장애를 텔레그램으로 통보한다(06-OPERATIONS).
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/favicon.ico").permitAll()
                        .requestMatchers("/login").permitAll()
                        .anyRequest().authenticated())

                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                        .permitAll())

                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))

                // CSRF 는 기본 활성이며 끄지 않는다. 상태 변경은 전부 POST 다.
                .csrf(Customizer.withDefaults())

                .sessionManagement(session -> session
                        // 로그인 시 세션 ID 를 교체한다. 공격자가 미리 심어둔 세션으로
                        // 로그인 후 권한을 물려받는 고정 공격을 막는다.
                        .sessionFixation(SessionFixationProtectionStrategy::migrateSession)
                        // 관리자 1명이므로 동시 세션을 1개로 제한한다. 두 곳에서 동시에
                        // 재고를 조작하는 상황 자체를 만들지 않는다.
                        .maximumSessions(1)
                        .maxSessionsPreventsLogin(false))

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

    /** 유휴 세션 만료 시간을 적용한다. */
    @Bean
    org.springframework.boot.web.servlet.ServletContextInitializer sessionTimeoutInitializer() {
        return servletContext ->
                servletContext.setSessionTimeout((int) SESSION_TIMEOUT.toMinutes());
    }
}
