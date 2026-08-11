package kr.suhsaechan.palim.automation.influencer.ai;

/** AI 심사 설정 키. */
public final class AiConfigKeys {

    public static final String CATEGORY = "INFLUENCER_AI";

    private static final String P = "influencer.ai.";

    public static final String MODEL = P + "model";
    public static final String PROMPT_VERSION = P + "promptVersion";
    public static final String POINTS_BRAND_SAFETY = P + "points.brandSafety";
    public static final String POINTS_CAMPAIGN_FIT = P + "points.campaignFit";
    public static final String POINTS_AUDIENCE_QUALITY = P + "points.audienceQuality";
    public static final String REVIEW_TOP_N = P + "reviewTopN";
    public static final String VIDEOS_PER_CHANNEL = P + "videosPerChannel";
    public static final String COMMENTS_PER_VIDEO = P + "commentsPerVideo";
    public static final String TRANSCRIPT_MAX_CHARS = P + "transcriptMaxChars";
    public static final String COOLDOWN_SECONDS = P + "limit.cooldownSeconds";
    public static final String DAILY_CALL_LIMIT = P + "limit.dailyCallLimit";

    private AiConfigKeys() {
    }
}
