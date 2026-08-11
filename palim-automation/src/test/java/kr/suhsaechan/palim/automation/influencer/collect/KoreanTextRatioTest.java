package kr.suhsaechan.palim.automation.influencer.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KoreanTextRatioTest {

    @Test
    @DisplayName("한글만 있으면 1.0 이다")
    void 한글_전용() {
        assertThat(KoreanTextRatio.of("캠핑 브이로그")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("영문만 있으면 0.0 이다")
    void 영문_전용() {
        assertThat(KoreanTextRatio.of("Daily Camping Vlog")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("공백·숫자·이모지는 분모에서 빠진다 — 있으면 국내 채널이 부당하게 낮게 나온다")
    void 비문자_제외() {
        assertThat(KoreanTextRatio.of("캠핑 2026 🔥")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("섞이면 문자 기준 비율이 나온다")
    void 혼합() {
        // 한글 2 + 영문 2 = 4 문자 중 한글 2
        assertThat(KoreanTextRatio.of("캠핑 TV")).isCloseTo(0.5, within(1e-9));
    }

    @Test
    @DisplayName("판정할 문자가 없으면 0 이다")
    void 빈_입력() {
        assertThat(KoreanTextRatio.of("", null, "123 !!")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("여러 필드를 합쳐 판정한다 — 제목이 영문이어도 설명이 한글이면 국내 채널이다")
    void 복수_필드() {
        assertThat(KoreanTextRatio.of("CampingTV", "안녕하세요 캠핑 채널입니다"))
                .isGreaterThan(0.5);
    }
}
