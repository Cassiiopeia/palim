package kr.suhsaechan.palim.automation.influencer.taxonomy;

import java.util.List;

/**
 * 자체 카테고리 한 건.
 *
 * @param coefficient  구독자 1명당 추정 광고 단가(원). 구매 전환이 직접적인 분야일수록 높다
 * @param seedKeywords 발굴 검색에 쓰는 시드. 롱테일일수록 중견 채널이 잡힌다
 */
public record TaxonomyCategory(
        String code,
        String name,
        double coefficient,
        List<String> seedKeywords) {
}
