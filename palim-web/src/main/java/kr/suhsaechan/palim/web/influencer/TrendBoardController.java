package kr.suhsaechan.palim.web.influencer;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import kr.suhsaechan.palim.automation.influencer.taxonomy.TaxonomyCategory;
import kr.suhsaechan.palim.automation.influencer.taxonomy.TaxonomyProvider;
import kr.suhsaechan.palim.automation.influencer.trend.TrendAggregationService;
import kr.suhsaechan.palim.automation.influencer.trend.TrendConfigKeys;
import kr.suhsaechan.palim.automation.influencer.trend.TrendKeyword;
import kr.suhsaechan.palim.common.config.ConfigReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 트렌드 보드 (#45).
 *
 * <p>외부 서비스를 붙이지 않는다 — 우리가 매일 긁는 것 자체가 트렌드 데이터다. 발굴 대상이
 * 유튜브이므로 유튜브 코퍼스가 오히려 정확한 소스이고, 비용도 리스크도 없다.
 *
 * <p>이 화면의 쓸모는 두 가지다. 캠페인 소재를 찾는 것, 그리고 <b>급상승 키워드가 발굴 시드로
 * 자동 환류되고 있는지 확인</b>하는 것.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class TrendBoardController {

    private final TrendAggregationService trendAggregationService;
    private final TaxonomyProvider taxonomyProvider;
    private final ConfigReader config;

    @GetMapping("/influencer/trends")
    public String board(@RequestParam(required = false) String category, Model model) {
        model.addAttribute("title", "트렌드 보드");

        var categories = new LinkedHashMap<String, String>();
        categories.put(TrendKeyword.ALL_CATEGORIES, "전체");
        for (TaxonomyCategory taxonomy : taxonomyProvider.categories()) {
            categories.put(taxonomy.code(), taxonomy.name());
        }
        model.addAttribute("categories", categories);

        String selected = category != null && categories.containsKey(category)
                ? category
                : TrendKeyword.ALL_CATEGORIES;
        model.addAttribute("selectedCategory", selected);

        LocalDate week = trendAggregationService.latestWeek().orElse(null);
        model.addAttribute("weekStart", week);

        if (week == null) {
            model.addAttribute("rows", List.of());
            model.addAttribute("rising", List.of());
            return "influencer/trends";
        }

        int limit = config.getInt(TrendConfigKeys.BOARD_LIMIT);
        List<TrendKeyword> top = trendAggregationService.findTop(week, selected, limit);
        model.addAttribute("rows", top.stream().map(TrendRowView::of).toList());
        model.addAttribute("rising", trendAggregationService.findRising(selected, limit).stream()
                .map(TrendRowView::of).toList());
        return "influencer/trends";
    }

    /**
     * 수동 집계.
     *
     * <p>주간 배치를 기다리지 않고 지금까지 수집된 것으로 보드를 채운다. 데이터가 막 쌓이기
     * 시작한 초기에 결과를 확인하는 통로다.
     */
    @PostMapping("/influencer/trends/aggregate")
    public String aggregate(@RequestParam(required = false) LocalDate weekStart,
                            RedirectAttributes redirectAttributes) {
        LocalDate target = weekStart != null ? weekStart : trendAggregationService.lastWeekStart();
        int saved = trendAggregationService.aggregate(target);

        redirectAttributes.addFlashAttribute("flashSuccess", saved == 0
                ? "%s 주에 집계할 영상이 없습니다.".formatted(target)
                : "%s 주 키워드 %d건을 집계했습니다.".formatted(target, saved));
        return "redirect:/influencer/trends";
    }

    /** 화면용 행. */
    public record TrendRowView(String keyword, int frequency, int prevFrequency,
                               double growthRatio, boolean isNew) {

        static TrendRowView of(TrendKeyword keyword) {
            return new TrendRowView(keyword.getKeyword(), keyword.getFrequency(),
                    keyword.getPrevFrequency(), keyword.growthRatio(), keyword.isNew());
        }

        /** 두 단어 묶음 — 롱테일이라 발굴 시드로 더 유용하다. */
        public boolean phrase() {
            return keyword.contains(" ");
        }

        /** 증가율 표시. 신규는 배율이 의미가 없어 별도 표기한다. */
        public String growthText() {
            if (isNew) {
                return "신규";
            }
            double percent = (growthRatio - 1) * 100;
            return "%s%.0f%%".formatted(percent >= 0 ? "+" : "", percent);
        }

        public boolean up() {
            return isNew || growthRatio > 1.0;
        }
    }

}
