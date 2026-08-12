package kr.suhsaechan.palim.automation.influencer.trend;

/** 트렌드 집계 설정 키. */
public final class TrendConfigKeys {

    public static final String CATEGORY = "INFLUENCER_TREND";

    private static final String P = "influencer.trend.";

    public static final String ENABLED = P + "enabled";
    public static final String MIN_FREQUENCY = P + "minFrequency";
    public static final String RISING_MIN_GROWTH = P + "risingMinGrowth";
    public static final String SEED_FEEDBACK_ENABLED = P + "seedFeedbackEnabled";
    public static final String SEED_FEEDBACK_LIMIT = P + "seedFeedbackLimit";
    public static final String BOARD_LIMIT = P + "boardLimit";

    private TrendConfigKeys() {
    }
}
