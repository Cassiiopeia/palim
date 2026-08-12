package kr.suhsaechan.palim.automation.influencer.rising;

/** 라이징 레이더 설정 키. */
public final class RisingConfigKeys {

    public static final String CATEGORY = "INFLUENCER_RISING";

    private static final String P = "influencer.rising.";

    public static final String WEEKLY_NOTIFICATION_ENABLED = P + "weeklyNotificationEnabled";
    public static final String NOTIFICATION_LOOKBACK_DAYS = P + "notificationLookbackDays";
    public static final String RADAR_LIMIT = P + "radarLimit";

    private RisingConfigKeys() {
    }
}
