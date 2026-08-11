package kr.suhsaechan.palim.automation.influencer.scoring;

/**
 * 스코어링 설정 키.
 *
 * <p>문자열 리터럴을 여기저기 쓰면 오타 하나로 조용히 기본값이 사라진다. 키는 여기 한 곳에만
 * 둔다 — 정의를 선언하는 곳과 값을 읽는 곳이 같은 상수를 쓰게 하는 것이 목적이다.
 */
public final class ScoringConfigKeys {

    /** 설정 화면의 그룹명. */
    public static final String CATEGORY = "INFLUENCER_SCORING";

    private static final String P = "influencer.scoring.";

    public static final String SHORTS_MAX_SECONDS = P + "shortsMaxSeconds";
    public static final String WINDOW_SIZE = P + "windowSize";

    public static final String HARD_MAX_DAYS_SINCE_UPLOAD = P + "hardFilter.maxDaysSinceUpload";
    public static final String HARD_MIN_LONGFORM_COUNT = P + "hardFilter.minLongformCount";

    public static final String RULE_REACH_POINTS = P + "rule.reach.points";
    public static final String RULE_VSR_CURVE = P + "rule.vsr.curve";
    public static final String RULE_MOMENTUM_TREND_CURVE = P + "rule.momentum.trendCurve";
    public static final String RULE_MOMENTUM_PEAK_CURVE = P + "rule.momentum.peakCurve";
    public static final String RULE_MOMENTUM_CRASH_THRESHOLD = P + "rule.momentum.crashThreshold";
    public static final String RULE_MOMENTUM_CRASH_CURVE = P + "rule.momentum.crashCurve";
    public static final String RULE_ENGAGEMENT_COMMENT_WEIGHT = P + "rule.engagement.commentWeight";
    public static final String RULE_ENGAGEMENT_CURVE = P + "rule.engagement.curve";
    public static final String RULE_ACTIVITY_UPLOADS_POINTS = P + "rule.activity.uploadsPoints";
    public static final String RULE_ACTIVITY_UPLOADS_TARGET = P + "rule.activity.uploadsTarget";
    public static final String RULE_ACTIVITY_RECENCY_CURVE = P + "rule.activity.recencyCurve";
    public static final String RULE_STABILITY_CURVE = P + "rule.stability.curve";

    public static final String RISING_VSR_HEAT_CURVE = P + "rising.vsrHeatCurve";
    public static final String RISING_ACCEL_CURVE = P + "rising.accelCurve";
    public static final String RISING_VELOCITY_CURVE = P + "rising.velocityCurve";
    public static final String RISING_BURST_CURVE = P + "rising.burstCurve";
    public static final String RISING_UNTAPPED_POINTS = P + "rising.untappedPoints";
    public static final String RISING_UNTAPPED_MAX_PAID_RATIO = P + "rising.untappedMaxPaidRatio";
    public static final String RISING_UNTAPPED_MAX_SUBSCRIBERS = P + "rising.untappedMaxSubscribers";
    public static final String RISING_BADGE_THRESHOLD = P + "rising.badgeThreshold";

    public static final String GRADE_S = P + "grade.s";
    public static final String GRADE_A = P + "grade.a";
    public static final String GRADE_B = P + "grade.b";
    public static final String GRADE_C = P + "grade.c";

    public static final String CPV_DEFAULT_COEFFICIENT = P + "cpv.defaultCoefficient";
    public static final String CPV_CATEGORY_COEFFICIENTS = P + "cpv.categoryCoefficients";

    /** 채점에 쓰인 기준을 점수 행에 남기기 위한 버전 문자열. 루브릭을 바꾸면 올린다. */
    public static final String RUBRIC_VERSION = P + "rubricVersion";

    private ScoringConfigKeys() {
    }
}
