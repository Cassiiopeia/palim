package kr.suhsaechan.palim.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 해싱 설정.
 *
 * <p>필터체인은 {@code palim-web} 의 책임이지만 해싱 방식은 인증 도메인의 관심사이므로 여기 둔다.
 * 이렇게 하면 {@code palim-auth} 가 화면 계층의 빈에 의존하지 않는다.
 *
 * <p>{@code createDelegatingPasswordEncoder} 는 해시에 {@code {bcrypt}} 같은 접두사를 붙인다.
 * 나중에 알고리즘을 교체해도 기존 해시를 그대로 검증할 수 있다.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
