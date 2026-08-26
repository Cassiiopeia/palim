package kr.suhsaechan.palim.automation.influencer;

import kr.suhsaechan.palim.common.config.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 인플루언서 기능을 <b>쓸 것인가</b>.
 *
 * <p>이 제품은 재고 대조 전용으로 방향을 틀었다. 인플루언서는 아직 준비 중이라 기본으로 꺼져
 * 있고, 설정 화면에서 켜면 그대로 되살아난다 — <b>코드는 한 줄도 지우지 않는다.</b>
 *
 * <p><b>판단을 한 곳에 모으는 이유.</b> 이 값을 봐야 하는 자리가 셋이다 — 주소를 막는 곳,
 * 메뉴를 그리는 곳, 저절로 도는 것을 멈추는 곳. 각자 설정 키를 따로 읽으면 한 곳만 고쳐도
 * 나머지가 어긋나, 「메뉴에는 없는데 주소는 열려 있는」 어중간한 상태가 생긴다.
 *
 * <p><b>읽는 시점이 중요하다.</b> 빈을 만들 때 읽으면 설정을 DB 에 심는 초기화가 아직 돌지
 * 않아 앱이 뜨지 않는다. 그래서 쓸 때마다 읽는다 — 덤으로 설정을 바꾸면 재기동 없이 먹는다.
 */
@Component
@RequiredArgsConstructor
public class InfluencerFeature {

    /** 설정 화면에서 이 기능을 한 묶음으로 보여줄 이름. */
    public static final String CATEGORY = "INFLUENCER";

    /** 마스터 스위치. 이 하나가 아래 세부 설정 전부를 지배한다. */
    public static final String ENABLED = "influencer.enabled";

    private final ConfigReader config;

    public boolean isEnabled() {
        return config.getBoolean(ENABLED);
    }
}
