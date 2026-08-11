package kr.suhsaechan.palim.automation.influencer.scoring;

import static kr.suhsaechan.palim.automation.influencer.scoring.ScoringConfigKeys.*;

import java.util.List;
import kr.suhsaechan.palim.common.config.ConfigDefinition;
import kr.suhsaechan.palim.common.config.ConfigDefinitionProvider;
import org.springframework.stereotype.Component;

/**
 * 스코어링 설정의 기본값 정의 — <b>루브릭 초기값의 유일한 원본</b>.
 *
 * <p>여기 값은 국내 유튜브 실측 감각에 기반한 출발점이지 검증된 값이 아니다. 발주사와
 * 캘리브레이션(정답셋 15~20개 대조)을 거쳐 화면에서 조정하며, 조정 결과는 DB 에 남고 이 코드는
 * 다시 쓰이지 않는다. 배포해도 기존 값을 덮어쓰지 않는다.
 *
 * <p>curve 값은 {@link PiecewiseLinear} 제어점 {@code [[x,y],...]} 이며 x 오름차순이어야 한다.
 */
@Component
public class ScoringConfigDefinitions implements ConfigDefinitionProvider {

    @Override
    public List<ConfigDefinition> definitions() {
        return List.of(
                // ── 공통 ────────────────────────────────────────────────
                ConfigDefinition.integer(SHORTS_MAX_SECONDS, 60, 1, 600, CATEGORY,
                        "쇼츠 판정 기준(초)",
                        "이 길이 이하는 쇼츠로 분리해 점수 계산에서 제외한다. 쇼츠를 롱폼과 섞으면 "
                                + "조회수 자릿수가 달라 참여율·도달 효율이 모두 왜곡된다.", 1),
                ConfigDefinition.integer(WINDOW_SIZE, 50, 10, 200, CATEGORY,
                        "관측 창(최근 롱폼 개수)",
                        "지표를 계산할 때 최근 몇 개 영상을 볼지. 크게 잡으면 안정적이지만 최근 변화에 "
                                + "둔감해진다.", 2),
                ConfigDefinition.text(RUBRIC_VERSION, "v1", CATEGORY,
                        "루브릭 버전",
                        "채점에 쓰인 기준의 이름표. 기준을 바꿨으면 올린다 — 이전 기준으로 매긴 점수와 "
                                + "섞이지 않게 하는 표식이다.", 3),

                // ── 하드 탈락 ───────────────────────────────────────────
                ConfigDefinition.integer(HARD_MAX_DAYS_SINCE_UPLOAD, 90, 7, 730, CATEGORY,
                        "탈락: 최대 무업로드 일수",
                        "마지막 업로드가 이보다 오래됐으면 점수를 매기지 않고 후보에서 뺀다.", 10),
                ConfigDefinition.integer(HARD_MIN_LONGFORM_COUNT, 5, 1, 50, CATEGORY,
                        "탈락: 최소 롱폼 개수",
                        "표본이 이보다 적으면 중앙값이 의미가 없어 채점하지 않는다.", 11),

                // ── 룰 점수 (합 70) ────────────────────────────────────
                ConfigDefinition.decimal(RULE_REACH_POINTS, 14.0, 0, 40, CATEGORY,
                        "배점: 실도달량",
                        "캠페인 목표 도달 구간 대비 실제 조회수 중앙값. 구간 안이면 만점, 밖이면 로그로 "
                                + "감쇠한다. 비율이 아니라 '실제 몇 명이 보는가'다.", 20),
                ConfigDefinition.json(RULE_VSR_CURVE,
                        "[[0.0,0.0],[0.08,2.0],[0.15,5.0],[0.3,9.0],[0.5,14.0]]", CATEGORY,
                        "배점 곡선: 도달 효율(VSR)",
                        "조회수 중앙값 ÷ 구독자. 0.08 미만은 구독자만 남은 죽은 채널, 0.5 이상은 외부 "
                                + "유입까지 터지는 채널이다. [입력, 점수] 쌍의 목록.", 21),
                ConfigDefinition.json(RULE_MOMENTUM_TREND_CURVE,
                        "[[0.0,0.0],[0.7,1.5],[0.9,3.0],[1.2,4.5],[1.5,6.0]]", CATEGORY,
                        "배점 곡선: 모멘텀 - 추세",
                        "최근 10편 조회수 중앙값 ÷ 직전 10편. 1.0이 횡보, 1.5 이상이 상승세다.", 22),
                ConfigDefinition.json(RULE_MOMENTUM_PEAK_CURVE,
                        "[[0.0,0.0],[0.3,1.0],[0.5,2.5],[0.8,4.0],[1.0,5.0]]", CATEGORY,
                        "배점 곡선: 모멘텀 - 피크 대비",
                        "최근 5편이 이 채널의 전성기 대비 어디쯤인가. 낮으면 알고리즘에서 밀려난 "
                                + "상태다.", 23),
                ConfigDefinition.decimal(RULE_MOMENTUM_CRASH_THRESHOLD, 0.5, 0.1, 1.0, CATEGORY,
                        "급락 판정 임계",
                        "최근 5편이 직전 대비 이 비율 미만이면 급락으로 보고 0점 + 경고 배지를 붙인다. "
                                + "구독자가 많아도 알고리즘에서 벗어난 채널을 잡는 장치다.", 24),
                ConfigDefinition.json(RULE_MOMENTUM_CRASH_CURVE, "[[0.5,0.0],[0.8,3.0]]", CATEGORY,
                        "배점 곡선: 모멘텀 - 급락 없음",
                        "급락이 아닐 때 주는 점수 곡선.", 25),
                ConfigDefinition.integer(RULE_ENGAGEMENT_COMMENT_WEIGHT, 3, 1, 10, CATEGORY,
                        "댓글 가중치",
                        "참여율 계산에서 댓글 1개를 좋아요 몇 개로 칠지. 좋아요는 손가락 한 번이고 "
                                + "댓글은 문장을 쓰므로 무게가 다르다.", 26),
                ConfigDefinition.json(RULE_ENGAGEMENT_CURVE,
                        "[[0.0,0.0],[0.015,2.5],[0.03,5.0],[0.05,8.5],[0.08,12.0]]", CATEGORY,
                        "배점 곡선: 참여율",
                        "(좋아요 + 댓글×가중) ÷ 조회수의 중앙값. 3~5%가 보통, 8% 이상이 우수하다.", 27),
                ConfigDefinition.decimal(RULE_ACTIVITY_UPLOADS_POINTS, 5.0, 0, 20, CATEGORY,
                        "배점: 업로드 빈도",
                        "최근 90일 업로드 수가 목표치에 도달하면 주는 만점.", 28),
                ConfigDefinition.integer(RULE_ACTIVITY_UPLOADS_TARGET, 12, 1, 90, CATEGORY,
                        "업로드 빈도 목표(90일 기준)",
                        "이 횟수를 채우면 만점. 12회면 주 1회다.", 29),
                ConfigDefinition.json(RULE_ACTIVITY_RECENCY_CURVE,
                        "[[0.0,3.0],[7.0,3.0],[14.0,2.25],[30.0,1.5],[60.0,0.75],[90.0,0.0]]",
                        CATEGORY, "배점 곡선: 최신성",
                        "마지막 업로드 경과일. 오래될수록 감점한다.", 30),
                ConfigDefinition.json(RULE_STABILITY_CURVE,
                        "[[0.0,8.0],[0.4,8.0],[0.8,5.0],[1.5,2.0],[3.0,0.0]]", CATEGORY,
                        "배점 곡선: 안정성",
                        "조회수 사분위 변동계수. 낮을수록 매번 비슷하게 나온다는 뜻이고, 광고는 예측 "
                                + "가능성을 산다.", 31),

                // ── 라이징 지수 (합 100) ───────────────────────────────
                ConfigDefinition.json(RISING_VSR_HEAT_CURVE, "[[1.0,0.0],[1.5,30.0]]", CATEGORY,
                        "라이징: VSR 과열",
                        "조회수가 구독자를 넘어서는 상태. 알고리즘이 비구독자에게 뿌리는 중이며 "
                                + "구독자 유입 직전의 가장 강한 선행 신호다.", 40),
                ConfigDefinition.json(RISING_ACCEL_CURVE, "[[1.0,0.0],[2.0,25.0]]", CATEGORY,
                        "라이징: 가속도",
                        "최근 영상이 직전 대비 몇 배로 도는가.", 41),
                ConfigDefinition.json(RISING_VELOCITY_CURVE, "[[1.0,0.0],[3.0,20.0]]", CATEGORY,
                        "라이징: 조회 속도",
                        "업로드 후 일수당 조회수가 이 채널 자기 기준선의 몇 배인가.", 42),
                ConfigDefinition.json(RISING_BURST_CURVE, "[[1.0,0.0],[2.0,15.0]]", CATEGORY,
                        "라이징: 참여 폭발",
                        "최근 영상 댓글율이 채널 평균의 몇 배인가.", 43),
                ConfigDefinition.decimal(RISING_UNTAPPED_POINTS, 10.0, 0, 30, CATEGORY,
                        "라이징: 미개척 가점",
                        "아직 광고 협업 이력이 거의 없고 규모도 작은 채널에 주는 가점. 단가 협상력이 "
                                + "가장 좋은 구간이다.", 44),
                ConfigDefinition.decimal(RISING_UNTAPPED_MAX_PAID_RATIO, 0.05, 0, 1, CATEGORY,
                        "미개척 판정: 최대 광고 비율", "유료광고 비율이 이 값 이하일 때만 미개척으로 본다.", 45),
                ConfigDefinition.integer(RISING_UNTAPPED_MAX_SUBSCRIBERS, 100000, 1000, 10000000,
                        CATEGORY, "미개척 판정: 최대 구독자",
                        "구독자가 이 수 미만일 때만 미개척으로 본다.", 46),
                ConfigDefinition.decimal(RISING_BADGE_THRESHOLD, 70.0, 0, 100, CATEGORY,
                        "라이징 배지 임계",
                        "이 점수 이상이면 라이징 배지가 붙고 매일 스냅샷 대상이 되며 주간 알림에 "
                                + "포함된다.", 47),

                // ── 등급 ───────────────────────────────────────────────
                ConfigDefinition.integer(GRADE_S, 85, 0, 100, CATEGORY, "등급 S 기준", "총점 하한.", 50),
                ConfigDefinition.integer(GRADE_A, 70, 0, 100, CATEGORY, "등급 A 기준", "총점 하한.", 51),
                ConfigDefinition.integer(GRADE_B, 55, 0, 100, CATEGORY, "등급 B 기준", "총점 하한.", 52),
                ConfigDefinition.integer(GRADE_C, 40, 0, 100, CATEGORY, "등급 C 기준",
                        "총점 하한. 미만은 D.", 53),

                // ── 단가 추정 ──────────────────────────────────────────
                ConfigDefinition.decimal(CPV_DEFAULT_COEFFICIENT, 25.0, 1, 1000, CATEGORY,
                        "추정 단가 계수(구독자 1명당 원)",
                        "업계 관행 기반 추정치다. 실제 견적이 입력되면 그 값이 우선한다.", 60),
                ConfigDefinition.json(CPV_CATEGORY_COEFFICIENTS, "{}", CATEGORY,
                        "카테고리별 단가 계수",
                        "카테고리 코드별 계수 재정의. 예: {\"beauty\": 45.0}. 비워두면 기본 계수를 "
                                + "쓴다.", 61)
        );
    }
}
