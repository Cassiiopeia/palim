package kr.suhsaechan.palim.automation.influencer;

import java.util.List;
import kr.suhsaechan.palim.common.config.ConfigDefinition;
import kr.suhsaechan.palim.common.config.ConfigDefinitionProvider;
import org.springframework.stereotype.Component;

/**
 * 인플루언서 마스터 스위치 정의.
 *
 * <p>기동할 때 정의에는 있고 DB 에 없는 키를 초기화가 스스로 넣는다. 그래서 이 기능을 끄는 데
 * <b>마이그레이션도 화면 코드도 필요 없다</b> — 배포하면 꺼진 채로 시작한다.
 *
 * <p>초기화는 <b>이미 있는 값을 덮어쓰지 않는다.</b> 켜 둔 것이 배포할 때마다 꺼지면 이 구조가
 * 무의미해지기 때문이다.
 *
 * <p>기본값이 꺼짐인 이유 — 이 제품은 재고 대조 전용이고 인플루언서는 준비 중이다. 새로 올린
 * 사람이 쓰지 않을 화면과 <b>돈이 나가는 배치</b>를 먼저 만나서는 안 된다.
 */
@Component
public class InfluencerFeatureConfigDefinitions implements ConfigDefinitionProvider {

    @Override
    public List<ConfigDefinition> definitions() {
        return List.of(ConfigDefinition.bool(
                InfluencerFeature.ENABLED, false, InfluencerFeature.CATEGORY,
                "인플루언서 기능 사용",
                "끄면 왼쪽 메뉴에서 인플루언서가 사라지고, 주소로 직접 들어가도 막히며, "
                        + "밤마다 도는 수집·점수 계산과 주간 알림이 멈춥니다. "
                        + "모아 둔 채널·점수·설정은 그대로 남으므로 다시 켜면 이어서 씁니다. "
                        + "지금은 준비 중이라 꺼 두었습니다.",
                0));
    }
}
