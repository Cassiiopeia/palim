package kr.suhsaechan.palim.reconcile.filter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

/**
 * 날짜 조건의 값. <b>고정값과 상대값을 함께 받는다.</b>
 *
 * <p>대조는 매일 아침 스스로 돈다. 「유통기한 2026-08-22 이후」 를 박으면 그날만 맞고 다음
 * 날부터 조용히 어긋난다 — 그리고 그것은 「어제는 됐는데 오늘은 안 된다」 로만 드러나 원인을
 * 찾기 어렵다.
 *
 * <p><b>푸는 시점이 저장이 아니라 실행이다.</b> 저장할 때 풀면 저장한 날짜로 굳어 같은 문제가
 * 된다. 그래서 원래 표현을 그대로 들고 있다가 회차가 돌 때 그 회차의 기준 시각으로 푼다.
 *
 * @param raw 사람이 적은 그대로. 회차에 「무슨 규칙이었나」 를 남기려면 이것이 필요하다
 */
public record DateToken(String raw) {

    /** 업무 시간대. {@code BaseAtGranularity} 와 같은 값이다 — 한쪽만 바뀌면 하루가 어긋난다. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    /** 상대값의 최대 폭. 오타(자릿수 하나 더)를 조건으로 받아 두면 결과가 통째로 빈다. */
    private static final int MAX_OFFSET_DAYS = 36_500;

    private static final String TODAY_KO = "오늘";
    private static final String TODAY_EN = "TODAY";

    /** 읽을 수 없으면 비어 있다 — 저장에서 막고, 어디가 문제인지 화면이 가리킨다. */
    public static Optional<DateToken> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();
        return looksRelative(value) || isFixed(value)
                ? Optional.of(new DateToken(value))
                : Optional.empty();
    }

    /** 이 회차의 기준 시각으로 푼다. */
    public LocalDate resolve(Instant asOf) {
        String head = head(raw);
        if (head == null) {
            return LocalDate.parse(raw);
        }
        LocalDate today = asOf.atZone(BUSINESS_ZONE).toLocalDate();
        return today.plusDays(offsetDays());
    }

    /** 상대값인가. 화면이 날짜칸 대신 「오늘 ▾」 위젯을 그리는 데 쓴다. */
    public boolean isRelative() {
        return head(raw) != null;
    }

    /** 「오늘」 뒤에 붙는 날수. 상대값이 아니거나 「오늘」 뿐이면 0. */
    public long offsetDays() {
        String head = head(raw);
        if (head == null) {
            return 0;
        }
        String rest = raw.substring(head.length());
        if (rest.isEmpty()) {
            return 0;
        }
        return Long.parseLong(rest.charAt(0) == '+' ? rest.substring(1) : rest);
    }

    private static String head(String value) {
        if (value.toUpperCase(Locale.ROOT).startsWith(TODAY_EN)) {
            return value.substring(0, TODAY_EN.length());
        }
        if (value.startsWith(TODAY_KO)) {
            return TODAY_KO;
        }
        return null;
    }

    private static boolean looksRelative(String value) {
        String head = head(value);
        if (head == null) {
            return false;
        }
        String rest = value.substring(head.length());
        if (rest.isEmpty()) {
            return true;
        }
        if (rest.charAt(0) != '+' && rest.charAt(0) != '-') {
            return false;
        }
        String digits = rest.substring(1);
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return false;
        }
        try {
            return Long.parseLong(digits) <= MAX_OFFSET_DAYS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isFixed(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
