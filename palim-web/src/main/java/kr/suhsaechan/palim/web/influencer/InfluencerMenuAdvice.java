package kr.suhsaechan.palim.web.influencer;

import kr.suhsaechan.palim.automation.influencer.InfluencerFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 메뉴가 인플루언서를 그릴지 <b>모든 화면에</b> 알려 준다.
 *
 * <p>좌측 메뉴는 레이아웃에 있고 레이아웃은 화면 전부가 쓴다. 그래서 이 값을 어느 한 컨트롤러가
 * 채우는 방식으로는 안 된다 — <b>값을 안 채운 화면에서 레이아웃이 잘린다.</b> 이 저장소에는
 * 표현식이 터지면 500 이 아니라 <b>200 인 채로 페이지가 중간에서 끊기는</b> 함정이 있어서,
 * 잘려도 화면은 열린 것처럼 보인다.
 *
 * <p>그래서 어느 컨트롤러가 그리든 값이 있도록 여기서 한 번에 채운다.
 */
@ControllerAdvice
@RequiredArgsConstructor
public class InfluencerMenuAdvice {

    private final InfluencerFeature influencerFeature;

    @ModelAttribute("influencerEnabled")
    public boolean influencerEnabled() {
        return influencerFeature.isEnabled();
    }
}
