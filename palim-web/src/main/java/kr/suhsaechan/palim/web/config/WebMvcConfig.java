package kr.suhsaechan.palim.web.config;

import kr.suhsaechan.palim.web.audit.ViewAuditInterceptor;
import kr.suhsaechan.palim.web.audit.WebAuditRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 조회 감사 인터셉터 등록.
 *
 * <p>화면마다 조회 기록을 호출하게 하면 새 화면을 추가할 때 빠뜨리고, 빠뜨린 화면은 감사에서
 * 사라진다. 횡단 관심사로 다룬다.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final WebAuditRecorder webAuditRecorder;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ViewAuditInterceptor(webAuditRecorder))
                // 정적 자원과 SSE 를 걸러낸다. 화면 판정은 ScreenNames 가 한 번 더 하지만,
                // 인터셉터 자체를 타지 않게 하는 편이 낫다.
                .excludePathPatterns("/css/**", "/js/**", "/favicon.ico",
                        "/actuator/**", "/api/**", "/login", "/login/**");
    }
}
