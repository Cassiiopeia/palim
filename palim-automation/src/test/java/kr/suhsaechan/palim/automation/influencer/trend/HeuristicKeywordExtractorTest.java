package kr.suhsaechan.palim.automation.influencer.trend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HeuristicKeywordExtractorTest {

    private final HeuristicKeywordExtractor extractor = new HeuristicKeywordExtractor();

    @Test
    @DisplayName("영상 제목에서 명사를 뽑는다")
    void 기본_추출() {
        assertThat(extractor.extract("겨울 캠핑 난로 추천"))
                .contains("겨울", "캠핑", "난로")
                // 관용어는 단어 단위에서 빠진다
                .doesNotContain("추천");
    }

    @Test
    @DisplayName("두 단어 묶음도 함께 센다 — 트렌드는 조합에서 드러난다")
    void bigram() {
        assertThat(extractor.extract("겨울 캠핑 난로 추천"))
                .contains("겨울 캠핑", "캠핑 난로", "난로 추천");
    }

    @Test
    @DisplayName("조사를 잘라낸다")
    void 조사_절단() {
        assertThat(extractor.extract("캠핑장에서 요리를 해봤어요"))
                .contains("캠핑장", "요리");
    }

    @Test
    @DisplayName("명사가 조사와 같은 글자로 끝나도 자르지 않는다")
    void 조사_오절단_방지() {
        // "고기"의 "기", "이야기"의 "기" 를 자르면 안 된다
        assertThat(extractor.extract("삼겹살 고기 이야기"))
                .contains("삼겹살", "고기", "이야기");
        // "도시"의 "시", "바다"의 "다" 도 마찬가지
        assertThat(extractor.extract("도시 바다 여행")).contains("도시", "바다", "여행");
    }

    @Test
    @DisplayName("관용어가 트렌드 1위를 차지하지 못하게 단어 단위에서 거른다")
    void 불용어() {
        assertThat(extractor.extract("리뷰 브이로그 후기 진짜 완전"))
                .doesNotContain("리뷰", "브이로그", "후기", "진짜", "완전");
    }

    @Test
    @DisplayName("숫자·이모지·특수문자는 키워드가 되지 않는다")
    void 잡음_제거() {
        assertThat(extractor.extract("2026 캠핑 🔥 TOP5 !!!"))
                .contains("캠핑")
                .doesNotContain("2026", "5", "top");
    }

    @Test
    @DisplayName("같은 제목에 두 번 나온 단어를 두 번 세지 않는다")
    void 중복_제거() {
        assertThat(extractor.extract("캠핑 장비 캠핑 텐트"))
                .filteredOn(keyword -> keyword.equals("캠핑"))
                .hasSize(1);
    }

    @Test
    @DisplayName("빈 입력은 빈 결과다")
    void 빈_입력() {
        assertThat(extractor.extract(null)).isEmpty();
        assertThat(extractor.extract("   ")).isEmpty();
        assertThat(extractor.extract("!!! 123")).isEmpty();
    }
}
