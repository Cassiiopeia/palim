package kr.suhsaechan.palim.automation.influencer.youtube;

/** YouTube 연동 설정 키. */
public final class YoutubeConfigKeys {

    public static final String CATEGORY = "INFLUENCER_YOUTUBE";

    private static final String P = "influencer.youtube.";

    public static final String QUOTA_DAILY_LIMIT = P + "quota.dailyLimit";
    public static final String QUOTA_SEARCH_BUDGET = P + "quota.searchBudget";
    public static final String REGION_CODE = P + "regionCode";
    public static final String RELEVANCE_LANGUAGE = P + "relevanceLanguage";
    public static final String MIN_SUBSCRIBER_COUNT = P + "discovery.minSubscriberCount";
    public static final String MIN_KOREAN_RATIO = P + "discovery.minKoreanRatio";
    public static final String VIDEO_FETCH_LIMIT = P + "collect.videoFetchLimit";
    public static final String REQUEST_TIMEOUT_SECONDS = P + "requestTimeoutSeconds";

    private YoutubeConfigKeys() {
    }
}
