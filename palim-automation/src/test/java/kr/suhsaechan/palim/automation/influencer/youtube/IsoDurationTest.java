package kr.suhsaechan.palim.automation.influencer.youtube;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IsoDurationTest {

    @Test
    @DisplayName("시·분·초 조합을 초로 변환한다")
    void 기간_변환() {
        assertThat(IsoDuration.toSeconds("PT45S")).isEqualTo(45);
        assertThat(IsoDuration.toSeconds("PT1M")).isEqualTo(60);
        assertThat(IsoDuration.toSeconds("PT12M30S")).isEqualTo(750);
        assertThat(IsoDuration.toSeconds("PT1H2M3S")).isEqualTo(3723);
    }

    @Test
    @DisplayName("쇼츠 경계값이 정확히 나온다 — 여기서 틀리면 지표 전체가 오염된다")
    void 쇼츠_경계() {
        assertThat(IsoDuration.toSeconds("PT60S")).isEqualTo(60);
        assertThat(IsoDuration.toSeconds("PT1M1S")).isEqualTo(61);
    }

    @Test
    @DisplayName("파싱 실패는 0 이 아니라 -1 이다 — 0 이면 모든 영상이 쇼츠가 된다")
    void 파싱_실패() {
        assertThat(IsoDuration.toSeconds(null)).isEqualTo(-1);
        assertThat(IsoDuration.toSeconds("")).isEqualTo(-1);
        assertThat(IsoDuration.toSeconds("30분")).isEqualTo(-1);
    }

    @Test
    @DisplayName("라이브 예정 영상의 P0D 는 0초다")
    void 라이브_예정() {
        assertThat(IsoDuration.toSeconds("P0D")).isZero();
    }
}
