package kr.suhsaechan.palim.automation.influencer.batch;

/** 인플루언서 배치 설정 키. */
public final class InfluencerBatchConfigKeys {

    public static final String CATEGORY = "INFLUENCER_BATCH";

    private static final String P = "influencer.batch.";

    public static final String ENABLED = P + "enabled";
    public static final String KEYWORD_LIMIT = P + "keywordLimitPerRun";
    public static final String FEATURED_LIMIT = P + "featuredLimitPerRun";
    public static final String REFRESH_LIMIT = P + "refreshLimitPerRun";
    public static final String CHART_CATEGORY_IDS = P + "chartCategoryIds";
    public static final String TIER_HOT_HOURS = P + "tier.hotHours";
    public static final String TIER_WARM_HOURS = P + "tier.warmHours";
    public static final String TIER_COLD_HOURS = P + "tier.coldHours";
    public static final String TIER_RISING_HOURS = P + "tier.risingHours";

    private InfluencerBatchConfigKeys() {
    }
}
