package kr.suhsaechan.palim.web.influencer;

import java.security.Principal;
import java.util.UUID;
import kr.suhsaechan.palim.automation.influencer.domain.Campaign;
import kr.suhsaechan.palim.automation.influencer.domain.ReviewDecision;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 인플루언서 등급표·심사 화면 (#41).
 *
 * <p>이 화면이 답해야 하는 질문은 하나다 — <b>"이 캠페인, 누구한테 거는 게 이득인가."</b>
 * 그래서 기본 정렬을 가성비순으로 둔다. 총점순은 종합 품질을 보는 보조 시각이다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class InfluencerController {

    private static final int ROW_LIMIT = 200;

    private final InfluencerAdminService influencerAdminService;
    private final ErrorMessageResolver errorMessageResolver;

    @GetMapping("/influencer/grades")
    public String grades(@RequestParam(required = false) UUID campaignId,
                         @RequestParam(required = false) GradeSort sort,
                         Model model) {
        var campaigns = influencerAdminService.findCampaigns();
        model.addAttribute("title", "인플루언서 등급표");
        model.addAttribute("campaigns", campaigns);
        model.addAttribute("sorts", GradeSort.values());

        if (campaigns.isEmpty()) {
            model.addAttribute("rows", java.util.List.of());
            return "influencer/grades";
        }

        UUID targetId = campaignId != null ? campaignId : campaigns.getFirst().getId();
        // 기본은 가성비순 — 사장님이 실제로 보는 정렬이다.
        GradeSort effectiveSort = sort != null ? sort : GradeSort.CPV;
        Campaign campaign = influencerAdminService.getCampaign(targetId);

        model.addAttribute("campaign", campaign);
        model.addAttribute("selectedCampaignId", targetId);
        model.addAttribute("selectedSort", effectiveSort);
        model.addAttribute("rows",
                influencerAdminService.findGrades(targetId, effectiveSort, ROW_LIMIT));
        model.addAttribute("reviewSummary", influencerAdminService.reviewSummary(targetId));
        return "influencer/grades";
    }

    @GetMapping("/influencer/campaigns/{campaignId}/channels/{channelId}")
    public String channelDetail(@PathVariable UUID campaignId, @PathVariable UUID channelId,
                                Model model) {
        model.addAttribute("title", "채널 상세");
        model.addAttribute("campaign", influencerAdminService.getCampaign(campaignId));
        model.addAttribute("detail",
                influencerAdminService.findChannelDetail(campaignId, channelId));
        model.addAttribute("decisions", ReviewDecision.values());
        return "influencer/channel";
    }

    @PostMapping("/influencer/campaigns/{campaignId}/channels/{channelId}/review")
    public String review(@PathVariable UUID campaignId, @PathVariable UUID channelId,
                         @RequestParam ReviewDecision decision,
                         @RequestParam(required = false) String note,
                         Principal principal, RedirectAttributes redirectAttributes) {
        try {
            influencerAdminService.review(campaignId, channelId, decision, note,
                    principal.getName());
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "%s(으)로 기록했습니다.".formatted(decision.displayName()));
        } catch (BusinessException e) {
            log.warn("심사 기록 실패 — 캠페인 {}, 채널 {}", campaignId, channelId, e);
            redirectAttributes.addFlashAttribute("flashError", errorMessageResolver.resolve(e));
        }
        return "redirect:/influencer/campaigns/%s/channels/%s".formatted(campaignId, channelId);
    }

    /**
     * 실제 견적 입력.
     *
     * <p>추정 단가는 업계 관행에 기댄 값이라 오차가 크다. 실제 견적이 들어오면 그 채널의 CPV 는
     * 추정이 아니라 실측이 되고, 이런 값이 쌓이면 계수 자체를 발주사 데이터로 다시 잡을 수 있다.
     */
    @PostMapping("/influencer/campaigns/{campaignId}/channels/{channelId}/quote")
    public String quote(@PathVariable UUID campaignId, @PathVariable UUID channelId,
                        @RequestParam long quotedPrice, RedirectAttributes redirectAttributes) {
        try {
            influencerAdminService.applyQuotedPrice(campaignId, channelId, quotedPrice);
            redirectAttributes.addFlashAttribute("flashSuccess", "견적을 반영했습니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("flashError", errorMessageResolver.resolve(e));
        }
        return "redirect:/influencer/campaigns/%s/channels/%s".formatted(campaignId, channelId);
    }
}
