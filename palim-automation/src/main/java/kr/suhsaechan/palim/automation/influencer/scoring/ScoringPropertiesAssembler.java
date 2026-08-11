package kr.suhsaechan.palim.automation.influencer.scoring;

import static kr.suhsaechan.palim.automation.influencer.scoring.ScoringConfigKeys.*;

import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.common.config.ConfigReader;
import tools.jackson.core.type.TypeReference;

/**
 * 설정 저장소 → {@link ScoringProperties} 조립.
 *
 * <p>계산 엔진은 설정을 파라미터로 받는 순수 함수라서, 값의 출처가 파일이든 DB든 알 필요가 없다.
 * 그 경계가 여기다 — 저장소가 바뀌면 이 클래스만 바뀐다.
 *
 * <p>매 채점마다 호출해도 되도록 {@link ConfigReader} 구현이 캐시를 갖는다. 그래야 화면에서
 * 가중치를 바꾼 즉시 다음 채점에 반영된다(재기동 불필요).
 */
public final class ScoringPropertiesAssembler {

    private static final TypeReference<List<List<Double>>> CURVE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Double>> COEFFICIENTS = new TypeReference<>() {
    };

    private ScoringPropertiesAssembler() {
    }

    public static ScoringProperties assemble(ConfigReader config) {
        return new ScoringProperties(
                config.getInt(SHORTS_MAX_SECONDS),
                config.getInt(WINDOW_SIZE),
                new ScoringProperties.HardFilterProps(
                        config.getInt(HARD_MAX_DAYS_SINCE_UPLOAD),
                        config.getInt(HARD_MIN_LONGFORM_COUNT)),
                new ScoringProperties.RuleProps(
                        config.getDouble(RULE_REACH_POINTS),
                        new ScoringProperties.CurveProps(config.getObject(RULE_VSR_CURVE, CURVE)),
                        new ScoringProperties.MomentumProps(
                                config.getObject(RULE_MOMENTUM_TREND_CURVE, CURVE),
                                config.getObject(RULE_MOMENTUM_PEAK_CURVE, CURVE),
                                config.getDouble(RULE_MOMENTUM_CRASH_THRESHOLD),
                                config.getObject(RULE_MOMENTUM_CRASH_CURVE, CURVE)),
                        new ScoringProperties.EngagementProps(
                                config.getInt(RULE_ENGAGEMENT_COMMENT_WEIGHT),
                                config.getObject(RULE_ENGAGEMENT_CURVE, CURVE)),
                        new ScoringProperties.ActivityProps(
                                config.getDouble(RULE_ACTIVITY_UPLOADS_POINTS),
                                config.getInt(RULE_ACTIVITY_UPLOADS_TARGET),
                                config.getObject(RULE_ACTIVITY_RECENCY_CURVE, CURVE)),
                        new ScoringProperties.CurveProps(
                                config.getObject(RULE_STABILITY_CURVE, CURVE))),
                new ScoringProperties.RisingProps(
                        config.getObject(RISING_VSR_HEAT_CURVE, CURVE),
                        config.getObject(RISING_ACCEL_CURVE, CURVE),
                        config.getObject(RISING_VELOCITY_CURVE, CURVE),
                        config.getObject(RISING_BURST_CURVE, CURVE),
                        config.getDouble(RISING_UNTAPPED_POINTS),
                        config.getDouble(RISING_UNTAPPED_MAX_PAID_RATIO),
                        config.getLong(RISING_UNTAPPED_MAX_SUBSCRIBERS),
                        config.getDouble(RISING_BADGE_THRESHOLD)),
                new ScoringProperties.GradeProps(
                        config.getInt(GRADE_S),
                        config.getInt(GRADE_A),
                        config.getInt(GRADE_B),
                        config.getInt(GRADE_C)),
                new ScoringProperties.CpvProps(
                        config.getDouble(CPV_DEFAULT_COEFFICIENT),
                        config.getObject(CPV_CATEGORY_COEFFICIENTS, COEFFICIENTS)));
    }
}
