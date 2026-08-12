package kr.suhsaechan.palim.automation.influencer.rising;

import static kr.suhsaechan.palim.automation.influencer.rising.RisingConfigKeys.*;

import java.util.List;
import kr.suhsaechan.palim.common.config.ConfigDefinition;
import kr.suhsaechan.palim.common.config.ConfigDefinitionProvider;
import org.springframework.stereotype.Component;

/** 라이징 레이더 설정. 판정 임계는 스코어링 설정(`influencer.scoring.rising.*`)에 있다. */
@Component
public class RisingConfigDefinitions implements ConfigDefinitionProvider {

    @Override
    public List<ConfigDefinition> definitions() {
        return List.of(
                ConfigDefinition.bool(WEEKLY_NOTIFICATION_ENABLED, false, CATEGORY,
                        "주간 알림 사용",
                        "월요일 아침 9시에 이번 주 신규 라이징 채널을 텔레그램으로 보낸다. "
                                + "텔레그램 연결과 채널 데이터가 준비된 뒤에 켠다.", 1),
                ConfigDefinition.integer(NOTIFICATION_LOOKBACK_DAYS, 7, 1, 90, CATEGORY,
                        "알림 대상 기간(일)",
                        "이 기간 안에 새로 감지된 채널만 알린다. 계속 레이더에 올라 있는 채널을 "
                                + "매주 다시 알리면 알림이 배경 소음이 되어 정작 새로 뜬 채널도 "
                                + "읽히지 않는다.", 2),
                ConfigDefinition.integer(RADAR_LIMIT, 50, 1, 500, CATEGORY,
                        "레이더 표시 개수",
                        "화면과 알림 집계에서 다룰 최대 채널 수.", 3)
        );
    }
}
