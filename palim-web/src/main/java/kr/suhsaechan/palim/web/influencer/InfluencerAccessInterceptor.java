package kr.suhsaechan.palim.web.influencer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import kr.suhsaechan.palim.automation.influencer.InfluencerFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.support.RequestContextUtils;

/**
 * 인플루언서 기능이 꺼져 있으면 <b>주소로도 못 들어가게</b> 막는다.
 *
 * <p>메뉴만 감추면 감춰지지 않는다. 이 화면들에는 접근 규칙이 하나도 없어서, 로그인한 사람은
 * <b>주소만 알면 그대로 다 쓴다.</b> 「감췄다고 믿는데 실제로는 도는」 상태가 정확히 그것이다.
 *
 * <p>특히 막아야 하는 것은 보는 화면이 아니라 <b>누르면 돈이 나가는 것</b>이다 — AI 심사와
 * 채널 수집은 외부 요금과 하루치 할당량을 쓴다. 화면을 감춘 뒤에도 그 입구가 남아 있으면
 * 감춘 의미가 없다.
 *
 * <h2>왜 필터가 아니라 인터셉터인가</h2>
 *
 * <p>인증이 끝난 뒤에만 판정하면 되고, 제외 규칙을 MVC 설정 한 곳에 모을 수 있다. 보안 필터
 * 체인에 넣으면 인증 흐름과 뒤엉켜 로그인 자체가 막히는 사고가 나기 쉽다(초기 비밀번호 차단이
 * 같은 이유로 인터셉터다).
 *
 * <h2>막고 끝내지 않는다</h2>
 *
 * <p>빈 거부 화면을 띄우면 사람은 <b>고장인지 정책인지</b> 구분하지 못한다. 홈으로 보내면서
 * 「꺼져 있다」 와 「어디서 켜는가」 를 함께 말한다.
 */
@Slf4j
@RequiredArgsConstructor
public class InfluencerAccessInterceptor implements HandlerInterceptor {

    private final InfluencerFeature influencerFeature;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        if (influencerFeature.isEnabled()) {
            return true;
        }

        log.debug("인플루언서 기능 꺼짐 — 접근 차단 {} {}",
                request.getMethod(), request.getRequestURI());

        FlashMap flash = new FlashMap();
        flash.put("flashError", "인플루언서 기능은 지금 꺼져 있습니다. "
                + "설정 → 시스템 설정 → 「인플루언서 기능」 에서 켤 수 있습니다.");
        RequestContextUtils.getFlashMapManager(request)
                .saveOutputFlashMap(flash, request, response);

        response.sendRedirect(request.getContextPath() + "/");
        return false;
    }
}
