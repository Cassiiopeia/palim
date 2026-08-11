package kr.suhsaechan.palim.automation.influencer.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuoteVerifierTest {

    private static final List<String> SOURCES = List.of(
            "오늘은 캠핑장에서 요리를 해볼 건데요 이 코펠이 정말 편합니다",
            "저 코펠 어디 제품인가요? 링크 좀 부탁드려요");

    @Test
    @DisplayName("원문에 있는 인용은 통과한다")
    void 원문_인용() {
        assertThat(QuoteVerifier.isGrounded("이 코펠이 정말 편합니다", SOURCES)).isTrue();
        assertThat(QuoteVerifier.isGrounded("어디 제품인가요", SOURCES)).isTrue();
    }

    @Test
    @DisplayName("지어낸 인용은 버려진다 — 이것이 이 검증의 목적이다")
    void 지어낸_인용() {
        assertThat(QuoteVerifier.isGrounded("이 제품은 부작용이 없습니다", SOURCES)).isFalse();
        assertThat(QuoteVerifier.isGrounded("구독자를 돈 주고 샀다는 의혹", SOURCES)).isFalse();
    }

    @Test
    @DisplayName("공백·줄바꿈 차이는 통과시킨다 — 서식 때문에 진짜 근거를 버리면 안 된다")
    void 서식_차이() {
        assertThat(QuoteVerifier.isGrounded("이  코펠이\n정말 편합니다", SOURCES)).isTrue();
        assertThat(QuoteVerifier.isGrounded("저코펠어디제품인가요", SOURCES)).isTrue();
    }

    @Test
    @DisplayName("너무 짧은 인용은 통과시키지 않는다 — 우연히 포함되어 검증이 무의미해진다")
    void 짧은_인용() {
        assertThat(QuoteVerifier.isGrounded("코펠", SOURCES)).isFalse();
        assertThat(QuoteVerifier.isGrounded("요리", SOURCES)).isFalse();
    }

    @Test
    @DisplayName("빈 값과 빈 원문은 통과하지 않는다")
    void 빈_입력() {
        assertThat(QuoteVerifier.isGrounded(null, SOURCES)).isFalse();
        assertThat(QuoteVerifier.isGrounded("   ", SOURCES)).isFalse();
        assertThat(QuoteVerifier.isGrounded("이 코펠이 정말 편합니다", List.of())).isFalse();
        assertThat(QuoteVerifier.isGrounded("이 코펠이 정말 편합니다",
                java.util.Collections.singletonList(null))).isFalse();
    }

    @Test
    @DisplayName("영문 대소문자 차이는 무시한다")
    void 대소문자() {
        assertThat(QuoteVerifier.isGrounded("CAMPING GEAR review",
                List.of("this camping gear review is honest"))).isTrue();
    }
}
