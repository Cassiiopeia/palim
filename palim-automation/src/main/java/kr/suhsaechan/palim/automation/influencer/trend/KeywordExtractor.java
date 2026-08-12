package kr.suhsaechan.palim.automation.influencer.trend;

import java.util.List;

/**
 * 한국어 텍스트에서 키워드를 뽑는다.
 *
 * <p>인터페이스로 분리한 이유는 <b>교체를 전제</b>하기 때문이다. 형태소 분석기(KOMORAN·은전한닢)를
 * 쓰면 정확도가 오르지만 사전 로딩 비용과 의존성이 붙는다. 영상 제목은 이미 키워드성 문구가
 * 많아("캠핑 장비 추천", "쿠션 발색 리뷰") 휴리스틱만으로도 쓸 만하므로 그것으로 시작하고,
 * 품질이 부족해지면 이 인터페이스 뒤에서 갈아끼운다.
 */
public interface KeywordExtractor {

    /**
     * @return 정규화된 키워드 목록. 같은 텍스트에서 중복 제거된 상태
     */
    List<String> extract(String text);
}
