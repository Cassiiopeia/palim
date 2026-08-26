package kr.suhsaechan.palim.web.config;

import kr.suhsaechan.palim.auth.AdminAccountService;
import kr.suhsaechan.palim.automation.influencer.InfluencerFeature;
import kr.suhsaechan.palim.web.audit.ViewAuditInterceptor;
import kr.suhsaechan.palim.web.audit.WebAuditRecorder;
import kr.suhsaechan.palim.web.influencer.InfluencerAccessInterceptor;
import kr.suhsaechan.palim.web.session.PasswordChangeRequiredInterceptor;
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
    private final AdminAccountService adminAccountService;
    private final InfluencerFeature influencerFeature;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 초기 비밀번호 차단이 먼저다. 변경 전에는 화면을 볼 수 없으므로 조회 감사를 남길
        // 이유도 없다 (#51).
        registry.addInterceptor(new PasswordChangeRequiredInterceptor(adminAccountService))
                .excludePathPatterns("/css/**", "/js/**", "/favicon.ico",
                        "/actuator/**", "/api/**", "/login", "/login/**");

        // 꺼 둔 기능은 주소로도 못 들어가야 한다. 메뉴만 감추면 로그인한 사람은 주소만 알면
        // 그대로 다 쓴다 — 특히 누르면 돈이 나가는 것들이 열려 있다.
        registry.addInterceptor(new InfluencerAccessInterceptor(influencerFeature))
                .addPathPatterns("/influencer/**");

        registry.addInterceptor(new ViewAuditInterceptor(webAuditRecorder))
                // 정적 자원과 SSE 를 걸러낸다. 화면 판정은 ScreenNames 가 한 번 더 하지만,
                // 인터셉터 자체를 타지 않게 하는 편이 낫다.
                .excludePathPatterns("/css/**", "/js/**", "/favicon.ico",
                        "/actuator/**", "/api/**", "/login", "/login/**");
    }
}
