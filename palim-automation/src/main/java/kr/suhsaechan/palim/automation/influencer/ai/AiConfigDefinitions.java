package kr.suhsaechan.palim.automation.influencer.ai;

import static kr.suhsaechan.palim.automation.influencer.ai.AiConfigKeys.*;

import java.util.List;
import kr.suhsaechan.palim.common.config.ConfigDefinition;
import kr.suhsaechan.palim.common.config.ConfigDefinitionProvider;
import org.springframework.stereotype.Component;

/** AI 심사 설정. 배점 합은 30 이어야 룰 70 과 합쳐 100 이 된다. */
@Component
public class AiConfigDefinitions implements ConfigDefinitionProvider {

    @Override
    public List<ConfigDefinition> definitions() {
        return List.of(
                ConfigDefinition.text(MODEL, "gpt-5.6-luna", CATEGORY,
                        "심사 모델",
                        "코드에 하드코딩하지 않는다 — 모델을 바꾸면 판단 성향이 달라지므로 "
                                + "바꾼 뒤에는 같은 채널을 다시 심사해 결과를 비교한다.", 1),
                ConfigDefinition.text(PROMPT_VERSION, "v1", CATEGORY,
                        "프롬프트 버전",
                        "리소스 파일 이름의 버전과 맞춘다. 프롬프트를 바꾸면 올린다 — 이전 프롬프트로 "
                                + "매긴 점수와 섞이지 않게 하는 표식이다.", 2),
                ConfigDefinition.decimal(POINTS_BRAND_SAFETY, 12.0, 0, 30, CATEGORY,
                        "배점: 브랜드 안전성",
                        "논란·과장 광고·댓글 차단 등 위험 신호. 가장 크게 잡는 이유는 실패 비용의 "
                                + "비대칭 때문이다 — 반응이 미지근한 것과 브랜드가 논란에 같이 "
                                + "언급되는 것은 손해의 크기가 다르다.", 10),
                ConfigDefinition.decimal(POINTS_CAMPAIGN_FIT, 10.0, 0, 30, CATEGORY,
                        "배점: 캠페인 적합도",
                        "채널 콘텐츠와 제품의 결이 맞는가. 시청자가 이 제품을 살 사람인가.", 11),
                ConfigDefinition.decimal(POINTS_AUDIENCE_QUALITY, 8.0, 0, 30, CATEGORY,
                        "배점: 시청자 반응 품질",
                        "댓글이 진짜인가. 구매 의향 표현 대 봇·무의미 댓글의 비중.", 12),
                ConfigDefinition.integer(REVIEW_TOP_N, 20, 1, 200, CATEGORY,
                        "심사 대상 상위 N명",
                        "룰 점수 상위 몇 명까지 AI 심사를 돌릴지. 채널당 약 2만 토큰이 들므로 "
                                + "이 값이 곧 비용이다.", 20),
                ConfigDefinition.integer(VIDEOS_PER_CHANNEL, 5, 1, 20, CATEGORY,
                        "채널당 분석 영상 수",
                        "최근 롱폼 몇 편의 자막·댓글을 볼지.", 21),
                ConfigDefinition.integer(COMMENTS_PER_VIDEO, 50, 5, 100, CATEGORY,
                        "영상당 댓글 수 (정렬별)",
                        "최신순·인기순 각각 이 개수만큼 모은다. 최신순이 논란 탐지의 핵심 신호다.", 22),
                ConfigDefinition.integer(TRANSCRIPT_MAX_CHARS, 8000, 500, 40000, CATEGORY,
                        "자막 길이 상한(자)",
                        "편당 이 길이로 잘라 보낸다. AI 입력 토큰이 비용의 대부분이다.", 23)
        );
    }
}
