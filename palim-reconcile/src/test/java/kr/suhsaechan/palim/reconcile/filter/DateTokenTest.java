package kr.suhsaechan.palim.reconcile.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 날짜는 고정값이면 안 된다.
 *
 * <p>대조는 <b>매일 아침 스스로 돈다.</b> 「유통기한 2026-08-22 이후」 를 박으면 그날만 맞고
 * 다음 날부터 조용히 어긋난다 — 그리고 그것은 「어제는 됐는데 오늘은 안 된다」 로만 드러나
 * 원인을 찾기 어렵다.
 */
class DateTokenTest {

    /** 2026-08-22 12:00 KST = 2026-08-22 03:00Z. 업무 시간대가 Asia/Seoul 임을 시험한다. */
    private static final Instant NOON_KST = Instant.parse("2026-08-22T03:00:00Z");

    @Test
    @DisplayName("「오늘」 은 회차가 도는 날로 풀린다")
    void resolvesToday() {
        assertThat(DateToken.parse("오늘").orElseThrow().resolve(NOON_KST))
                .isEqualTo(LocalDate.of(2026, 8, 22));
        assertThat(DateToken.parse("TODAY").orElseThrow().resolve(NOON_KST))
                .isEqualTo(LocalDate.of(2026, 8, 22));
    }

    @Test
    @DisplayName("「오늘+30」 · 「오늘-7」 로 앞뒤를 잡는다")
    void resolvesOffsets() {
        assertThat(DateToken.parse("오늘+30").orElseThrow().resolve(NOON_KST))
                .isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(DateToken.parse("TODAY-7").orElseThrow().resolve(NOON_KST))
                .isEqualTo(LocalDate.of(2026, 8, 15));
    }

    @Test
    @DisplayName("고정 날짜도 그대로 받는다 — 특정 시점을 못 박아야 할 때가 있다")
    void resolvesFixedDate() {
        assertThat(DateToken.parse("2026-01-31").orElseThrow().resolve(NOON_KST))
                .isEqualTo(LocalDate.of(2026, 1, 31));
    }

    @Test
    @DisplayName("업무 시간대는 Asia/Seoul 이다 — UTC 로 보면 하루가 어긋난다")
    void usesBusinessZone() {
        // 2026-08-21 15:30Z = 2026-08-22 00:30 KST. 한국에서는 이미 22일이다.
        Instant lateNight = Instant.parse("2026-08-21T15:30:00Z");
        assertThat(DateToken.parse("오늘").orElseThrow().resolve(lateNight))
                .isEqualTo(LocalDate.of(2026, 8, 22));
    }

    @Test
    @DisplayName("읽을 수 없는 값은 비어 있다 — 저장에서 막힌다")
    void rejectsGarbage() {
        assertThat(DateToken.parse("어제")).isEmpty();
        assertThat(DateToken.parse("오늘+")).isEmpty();
        assertThat(DateToken.parse("오늘+하루")).isEmpty();
        assertThat(DateToken.parse("2026-13-01")).isEmpty();
        assertThat(DateToken.parse("")).isEmpty();
        assertThat(DateToken.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("터무니없이 먼 상대값은 거부한다 — 오타를 조건으로 받아 두면 결과가 통째로 빈다")
    void rejectsAbsurdOffset() {
        assertThat(DateToken.parse("오늘+100000")).isEmpty();
        assertThat(DateToken.parse("오늘-100000")).isEmpty();
    }

    @Test
    @DisplayName("원래 표현을 그대로 들고 있다 — 회차에 「무슨 규칙이었나」 를 남겨야 한다")
    void keepsRawForm() {
        assertThat(DateToken.parse("오늘+30").orElseThrow().raw()).isEqualTo("오늘+30");
        assertThat(DateToken.parse("오늘+30").orElseThrow().isRelative()).isTrue();
        assertThat(DateToken.parse("2026-01-31").orElseThrow().isRelative()).isFalse();
    }
}
