package kr.suhsaechan.palim.reconcile.engine;

/** 정기 대조 시각 설정 키. */
public final class ReconcileScheduleKeys {

    public static final String CATEGORY = "RECONCILE";

    private static final String P = "reconcile.schedule.";

    public static final String HOUR = P + "hour";
    public static final String MINUTE = P + "minute";

    private ReconcileScheduleKeys() {
    }
}
