package kr.suhsaechan.palim.automation.influencer.trend;

import static kr.suhsaechan.palim.automation.influencer.trend.TrendConfigKeys.*;

import java.util.List;
import kr.suhsaechan.palim.common.config.ConfigDefinition;
import kr.suhsaechan.palim.common.config.ConfigDefinitionProvider;
import org.springframework.stereotype.Component;

/** 트렌드 보드 설정. */
@Component
public class TrendConfigDefinitions implements ConfigDefinitionProvider {

    @Override
    public List<ConfigDefinition> definitions() {
        return List.of(
                ConfigDefinition.bool(ENABLED, true, CATEGORY,
                        "주간 집계 사용",
                        "매주 월요일 새벽에 지난 주 영상 제목에서 키워드를 세어 저장한다. "
                                + "AI 를 쓰지 않는 순수 문자열 집계라 비용이 들지 않는다.", 1),
                ConfigDefinition.integer(MIN_FREQUENCY, 3, 1, 100, CATEGORY,
                        "최소 등장 횟수",
                        "이보다 적게 나온 말은 트렌드가 아니라 잡음이다. 낮추면 보드가 한 번씩 "
                                + "등장한 고유명사로 가득 찬다.", 2),
                ConfigDefinition.decimal(RISING_MIN_GROWTH, 1.5, 1.0, 10.0, CATEGORY,
                        "급상승 판정 배율",
                        "전주 대비 이 배율 이상 늘어야 급상승으로 본다. 1.5면 50% 증가다. "
                                + "전주에 없던 신규 키워드는 배율 대신 빈도로 평가한다.", 3),
                ConfigDefinition.bool(SEED_FEEDBACK_ENABLED, true, CATEGORY,
                        "발굴 시드 환류 사용",
                        "급상승 키워드를 발굴 검색 시드에 자동으로 추가한다. 새 트렌드가 등장하면 "
                                + "그 분야 채널을 찾아 라이징까지 감지하는 흐름이 자동으로 돈다.", 4),
                ConfigDefinition.integer(SEED_FEEDBACK_LIMIT, 5, 0, 50, CATEGORY,
                        "1회 환류 키워드 수",
                        "한 번에 시드로 추가할 키워드 수. 검색은 호출당 100 units 라 무제한으로 "
                                + "늘리면 할당량이 발굴에만 쓰인다.", 5),
                ConfigDefinition.integer(BOARD_LIMIT, 20, 5, 100, CATEGORY,
                        "보드 표시 개수", "카테고리별로 보여줄 키워드 수.", 6)
        );
    }
}
