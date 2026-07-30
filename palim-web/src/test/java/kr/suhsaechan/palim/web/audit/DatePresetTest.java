package kr.suhsaechan.palim.web.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DatePresetTest {

    /** 2026-07-30 은 목요일이다. */
    private static final LocalDate THURSDAY = LocalDate.of(2026, 7, 30);

    @Test
    void 오늘과_어제() {
        assertThat(DatePreset.TODAY.from(THURSDAY)).isEqualTo(THURSDAY);
        assertThat(DatePreset.TODAY.to(THURSDAY)).isEqualTo(THURSDAY);
        assertThat(DatePreset.YESTERDAY.from(THURSDAY)).isEqualTo(THURSDAY.minusDays(1));
        assertThat(DatePreset.YESTERDAY.to(THURSDAY)).isEqualTo(THURSDAY.minusDays(1));
    }

    @Test
    @DisplayName("이번 주는 월요일부터 오늘까지다")
    void 이번_주() {
        assertThat(DatePreset.THIS_WEEK.from(THURSDAY)).isEqualTo(LocalDate.of(2026, 7, 27));
        assertThat(DatePreset.THIS_WEEK.to(THURSDAY)).isEqualTo(THURSDAY);
    }

    @Test
    @DisplayName("지난 주는 지난 월요일부터 지난 일요일까지다")
    void 지난_주() {
        assertThat(DatePreset.LAST_WEEK.from(THURSDAY)).isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(DatePreset.LAST_WEEK.to(THURSDAY)).isEqualTo(LocalDate.of(2026, 7, 26));
    }

    @Test
    @DisplayName("월요일 당일에도 이번 주는 오늘 하루다 — previousOrSame 이어야 한다")
    void 월요일_경계() {
        LocalDate monday = LocalDate.of(2026, 7, 27);
        assertThat(DatePreset.THIS_WEEK.from(monday)).isEqualTo(monday);
        assertThat(DatePreset.LAST_WEEK.from(monday)).isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(DatePreset.LAST_WEEK.to(monday)).isEqualTo(LocalDate.of(2026, 7, 26));
    }

    @Test
    void 지난_달은_말일까지다() {
        assertThat(DatePreset.LAST_MONTH.from(THURSDAY)).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(DatePreset.LAST_MONTH.to(THURSDAY)).isEqualTo(LocalDate.of(2026, 6, 30));
    }
}
