package kr.suhsaechan.palim.automation.influencer.taxonomy;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.suhsaechan.palim.common.config.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/** 현재 카테고리 체계를 읽는다. 화면에서 바꾸면 다음 호출부터 반영된다. */
@Component
@RequiredArgsConstructor
public class TaxonomyProvider {

    private static final TypeReference<List<TaxonomyCategory>> CATEGORIES = new TypeReference<>() {
    };

    private final ConfigReader config;

    public List<TaxonomyCategory> categories() {
        return config.getObject(TaxonomyConfigKeys.CATEGORIES, CATEGORIES);
    }

    /** 카테고리별 단가 계수 — CPV 추정에 넘긴다. */
    public Map<String, Double> coefficients() {
        return categories().stream().collect(
                Collectors.toMap(TaxonomyCategory::code, TaxonomyCategory::coefficient));
    }

    /** 모든 시드 키워드를 평탄화. 발굴 커서를 만들 때 쓴다. */
    public List<String> allSeedKeywords() {
        return categories().stream()
                .map(TaxonomyCategory::seedKeywords)
                .flatMap(List::stream)
                .distinct()
                .toList();
    }

    public Map<String, TaxonomyCategory> byCode() {
        return categories().stream()
                .collect(Collectors.toMap(TaxonomyCategory::code, Function.identity()));
    }
}
