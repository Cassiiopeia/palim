package kr.suhsaechan.palim.automation.influencer.batch;

import static kr.suhsaechan.palim.automation.influencer.batch.InfluencerBatchConfigKeys.*;

import java.util.List;
import kr.suhsaechan.palim.common.config.ConfigDefinition;
import kr.suhsaechan.palim.common.config.ConfigDefinitionProvider;
import org.springframework.stereotype.Component;

/** 야간 배치 설정. */
@Component
public class InfluencerBatchConfigDefinitions implements ConfigDefinitionProvider {

    @Override
    public List<ConfigDefinition> definitions() {
        return List.of(
                ConfigDefinition.bool(ENABLED, false, CATEGORY,
                        "야간 배치 사용",
                        "발굴·갱신·채점을 매일 자동 실행한다. API 키를 등록하고 시드 키워드를 "
                                + "확정한 뒤에 켠다 — 기본은 꺼짐이다.", 1),
                ConfigDefinition.integer(KEYWORD_LIMIT, 20, 0, 200, CATEGORY,
                        "1회 검색 키워드 수",
                        "한 번 실행에서 돌릴 시드 키워드 개수. 검색은 호출당 100 units 라 이 값이 "
                                + "곧 검색 예산이다(20개 = 2,000 units).", 2),
                ConfigDefinition.integer(FEATURED_LIMIT, 50, 0, 500, CATEGORY,
                        "1회 추천 채널 확장 수",
                        "추천 채널을 훑어볼 기존 채널 개수. 호출당 1 unit 이다.", 3),
                ConfigDefinition.integer(REFRESH_LIMIT, 300, 0, 5000, CATEGORY,
                        "1회 지표 갱신 채널 수",
                        "한 번 실행에서 지표를 다시 읽을 채널 상한. 채널당 3~4 units 든다.", 4),
                ConfigDefinition.json(CHART_CATEGORY_IDS,
                        "[\"1\",\"10\",\"17\",\"20\",\"22\",\"23\",\"24\",\"26\",\"28\"]", CATEGORY,
                        "인기 차트 순회 카테고리",
                        "YouTube 카테고리 ID 목록. 기본값은 영화(1)·음악(10)·스포츠(17)·게임(20)·"
                                + "인물(22)·코미디(23)·엔터(24)·노하우/스타일(26)·과학기술(28) 이다. "
                                + "호출당 1 unit 이라 전부 돌려도 부담이 없다.", 5),
                ConfigDefinition.integer(TIER_RISING_HOURS, 24, 1, 8760, CATEGORY,
                        "갱신 주기: 라이징(시간)",
                        "폭발 조짐이 있는 채널. 며칠만 늦어도 단가가 오르므로 매일 본다.", 10),
                ConfigDefinition.integer(TIER_HOT_HOURS, 168, 1, 8760, CATEGORY,
                        "갱신 주기: 상위군(시간)", "기본 168시간 = 주 1회.", 11),
                ConfigDefinition.integer(TIER_WARM_HOURS, 336, 1, 8760, CATEGORY,
                        "갱신 주기: 중위군(시간)", "기본 336시간 = 2주.", 12),
                ConfigDefinition.integer(TIER_COLD_HOURS, 720, 1, 8760, CATEGORY,
                        "갱신 주기: 하위군(시간)", "기본 720시간 = 30일.", 13)
        );
    }
}
