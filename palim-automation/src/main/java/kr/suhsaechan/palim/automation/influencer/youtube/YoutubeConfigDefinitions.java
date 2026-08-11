package kr.suhsaechan.palim.automation.influencer.youtube;

import static kr.suhsaechan.palim.automation.influencer.youtube.YoutubeConfigKeys.*;

import java.util.List;
import kr.suhsaechan.palim.common.config.ConfigDefinition;
import kr.suhsaechan.palim.common.config.ConfigDefinitionProvider;
import org.springframework.stereotype.Component;

/**
 * YouTube 연동 설정 정의.
 *
 * <p>API 키는 여기 없다 — 비밀값은 DB·파일에 두지 않고 환경변수로만 주입한다
 * ({@code YOUTUBE_API_KEY}). 이 저장소는 공개 저장소이므로 예외를 두지 않는다.
 */
@Component
public class YoutubeConfigDefinitions implements ConfigDefinitionProvider {

    @Override
    public List<ConfigDefinition> definitions() {
        return List.of(
                ConfigDefinition.integer(QUOTA_DAILY_LIMIT, 10000, 100, 1000000, CATEGORY,
                        "일일 할당량(units)",
                        "YouTube Data API 의 하루 예산. 무료 기본값은 10,000 이며 상향 승인을 받으면 "
                                + "그 값으로 올린다. 이 선에 닿으면 배치가 스스로 멈추고 다음 날 "
                                + "이어서 진행한다.", 1),
                ConfigDefinition.integer(QUOTA_SEARCH_BUDGET, 2000, 0, 1000000, CATEGORY,
                        "검색 전용 예산(units)",
                        "키워드 검색은 호출당 100 units 로 압도적으로 비싸다. 별도 상한을 두지 않으면 "
                                + "발굴이 하루 예산을 다 먹고 정작 지표 갱신이 멈춘다. 2,000 이면 "
                                + "하루 20회 검색이다.", 2),
                ConfigDefinition.text(REGION_CODE, "KR", CATEGORY,
                        "지역 코드", "인기 차트·검색의 기준 지역. 국내 한정이므로 KR.", 3),
                ConfigDefinition.text(RELEVANCE_LANGUAGE, "ko", CATEGORY,
                        "검색 언어", "검색 결과의 우선 언어.", 4),
                ConfigDefinition.integer(MIN_SUBSCRIBER_COUNT, 5000, 0, 10000000, CATEGORY,
                        "발굴 최소 구독자",
                        "이보다 작은 채널은 후보로 등록하지 않는다. 너무 낮추면 수집 대상이 폭증해 "
                                + "할당량을 잡아먹는다.", 5),
                ConfigDefinition.decimal(MIN_KOREAN_RATIO, 0.3, 0, 1, CATEGORY,
                        "한글 비율 하한",
                        "채널 제목·설명의 한글 문자 비율. 국가 정보가 비공개인 채널을 국내 채널로 "
                                + "판정하는 보조 기준이다.", 6),
                ConfigDefinition.integer(VIDEO_FETCH_LIMIT, 50, 10, 200, CATEGORY,
                        "영상 수집 개수",
                        "채널당 최근 몇 개 영상을 가져올지. 관측 창보다 크게 잡아야 쇼츠를 걸러낸 뒤에도 "
                                + "롱폼 표본이 남는다.", 7),
                ConfigDefinition.integer(REQUEST_TIMEOUT_SECONDS, 10, 1, 120, CATEGORY,
                        "요청 타임아웃(초)",
                        "응답이 없을 때 기다리는 시간. 길게 잡으면 배치 전체가 한 채널에 묶인다.", 8)
        );
    }
}
