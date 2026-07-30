package kr.suhsaechan.palim.web.audit;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * 감사 로그 조회 기간 프리셋.
 *
 * <h2>기준 시간대는 KST 다</h2>
 *
 * <p>저장은 {@code timestamptz}(UTC)지만 발주자가 인지하는 "오늘"은 KST 기준이다. UTC 자정으로
 * 자르면 <b>KST 오전 9시 이전의 기록이 어제로 넘어가</b> 방금 한 작업이 목록에 없다.
 *
 * <h2>주 시작은 월요일이다</h2>
 *
 * <p>{@code TemporalAdjusters.previous(MONDAY)} 를 쓰면 수요일 기준으로 <b>이번 주</b> 월요일이
 * 나온다("직전 월요일"이므로). 지난 주를 구하려면 이번 주 월요일에서 7일을 빼야 한다.
 *
 * <p>{@link #CUSTOM} 은 화면에서 입력한 날짜를 그대로 쓰므로 여기서 범위를 계산하지 않는다.
 */
public enum DatePreset {

    TODAY("오늘"),
    YESTERDAY("어제"),
    THIS_WEEK("이번 주"),
    LAST_WEEK("지난 주"),
    THIS_MONTH("이번 달"),
    LAST_MONTH("지난 달"),
    CUSTOM("직접 지정");

    private final String displayName;

    DatePreset(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 시작 날짜(포함). {@code today} 는 KST 기준 오늘이다. */
    public LocalDate from(LocalDate today) {
        return switch (this) {
            case TODAY, CUSTOM -> today;
            case YESTERDAY -> today.minusDays(1);
            case THIS_WEEK -> firstDayOfWeek(today);
            case LAST_WEEK -> firstDayOfWeek(today).minusWeeks(1);
            case THIS_MONTH -> today.withDayOfMonth(1);
            case LAST_MONTH -> today.minusMonths(1).withDayOfMonth(1);
        };
    }

    /** 종료 날짜(포함). */
    public LocalDate to(LocalDate today) {
        return switch (this) {
            case TODAY, THIS_WEEK, THIS_MONTH, CUSTOM -> today;
            case YESTERDAY -> today.minusDays(1);
            case LAST_WEEK -> firstDayOfWeek(today).minusDays(1);
            case LAST_MONTH -> today.minusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
        };
    }

    private static LocalDate firstDayOfWeek(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
