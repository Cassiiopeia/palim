package kr.suhsaechan.palim.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 관리자 화면 접근 제어.
 *
 * <p>관리자 계정 1개로 로그인한다. 다중 사용자·권한 분리는 범위에 포함하지 않는다(F-09).
 *
 * <p>{@code /actuator/health}는 인증 없이 열어둔다. 시스템 자기 감시가 이 엔드포인트를
 * 폴링해 장애를 텔레그램으로 통보하기 때문이다(설계서 9.3).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/css/**", "/js/**", "/favicon.ico")
                        .permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .logout(Customizer.withDefaults())
                .build();
    }
}
