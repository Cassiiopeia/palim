package kr.suhsaechan.palim.web.influencer;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.automation.influencer.domain.Campaign;
import kr.suhsaechan.palim.automation.influencer.domain.CampaignRepository;
import kr.suhsaechan.palim.automation.influencer.domain.ChannelStatus;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannel;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannelRepository;
import kr.suhsaechan.palim.automation.influencer.discover.DiscoveryService;
import kr.suhsaechan.palim.automation.influencer.score.ScoringService;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.error.ErrorMessageResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 캠페인 관리 (#41).
 *
 * <p>캠페인은 채점의 전제다. "무슨 광고에 적합한가"라는 기준 없이 매긴 점수는 광고 집행에
 * 쓸모가 없기 때문에, 브리프를 먼저 만들지 않으면 등급표가 비어 있다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignRepository campaignRepository;
    private final InfluencerChannelRepository channelRepository;
    private final ScoringService scoringService;
    private final DiscoveryService discoveryService;
    private final kr.suhsaechan.palim.automation.influencer.ai.AiReviewService aiReviewService;
    private final ErrorMessageResolver errorMessageResolver;

    @GetMapping("/influencer/campaigns")
    public String list(Model model) {
        model.addAttribute("title", "캠페인 관리");
        model.addAttribute("campaigns", campaignRepository.findAll());
        model.addAttribute("channelCount",
                channelRepository.findAll().stream()
                        .filter(channel -> channel.getStatus() == ChannelStatus.ACTIVE)
                        .count());
        return "influencer/campaigns";
    }

    @PostMapping("/influencer/campaigns")
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String productCategory,
                         @RequestParam(required = false) String targetAudience,
                         @RequestParam(required = false) String sellingPoints,
                         @RequestParam(required = false) String exclusions,
                         @RequestParam long targetReachMin,
                         @RequestParam long targetReachMax,
                         @RequestParam long subscriberMin,
                         @RequestParam long subscriberMax,
                         RedirectAttributes redirectAttributes) {
        if (targetReachMin > targetReachMax || subscriberMin > subscriberMax) {
            redirectAttributes.addFlashAttribute("flashError",
                    "구간의 하한이 상한보다 큽니다.");
            return "redirect:/influencer/campaigns";
        }

        Campaign campaign = Campaign.of(name, productCategory, targetAudience, sellingPoints,
                exclusions, targetReachMin, targetReachMax, subscriberMin, subscriberMax);
        campaign.activate();
        campaignRepository.save(campaign);

        redirectAttributes.addFlashAttribute("flashSuccess",
                "캠페인을 만들었습니다. 채점을 실행하면 등급표가 채워집니다.");
        return "redirect:/influencer/campaigns";
    }

    /**
     * 수동 시드 등록.
     *
     * <p>발주사가 이미 아는 채널을 넣는 통로다. 발굴이 돌기 전에도 등급표를 볼 수 있고,
     * 캘리브레이션의 정답셋을 만드는 입구이기도 하다.
     */
    @PostMapping("/influencer/seeds")
    public String addSeeds(@RequestParam String channelIds,
                           RedirectAttributes redirectAttributes) {
        List<String> ids = List.of(channelIds.split("[\\s,]+")).stream()
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .toList();

        if (ids.isEmpty()) {
            redirectAttributes.addFlashAttribute("flashError", "채널 ID 를 입력하세요.");
            return "redirect:/influencer/campaigns";
        }

        try {
            int registered = discoveryService.registerManualSeeds(ids);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "%d개 중 %d개를 새로 등록했습니다.".formatted(ids.size(), registered));
        } catch (BusinessException e) {
            log.warn("수동 시드 등록 실패", e);
            redirectAttributes.addFlashAttribute("flashError", errorMessageResolver.resolve(e.getErrorCode(), e.messageArgs()));
        }
        return "redirect:/influencer/campaigns";
    }

    /**
     * AI 심층 심사 실행.
     *
     * <p>수동 트리거인 이유가 둘이다. 비용이 드는 단계라 사람이 명시적으로 눌러야 하고,
     * 자막 수집이 외부 도구에 의존해 언제든 막힐 수 있어 배치가 조용히 반복 시도하면
     * 차단을 자초한다.
     */
    @PostMapping("/influencer/campaigns/{campaignId}/ai-review")
    public String aiReview(@PathVariable UUID campaignId, RedirectAttributes redirectAttributes) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INFLUENCER_CAMPAIGN_NOT_FOUND, campaignId));
        try {
            int reviewed = aiReviewService.reviewTopCandidates(campaign);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    reviewed == 0
                            ? "새로 심사할 채널이 없습니다. 자료가 그대로면 기존 결과를 유지합니다."
                            : "%d개 채널을 AI 심사했습니다.".formatted(reviewed));
        } catch (BusinessException e) {
            log.warn("AI 심사 실패 — 캠페인 {}", campaignId, e);
            redirectAttributes.addFlashAttribute("flashError",
                    errorMessageResolver.resolve(e.getErrorCode(), e.messageArgs()));
        }
        return "redirect:/influencer/grades?campaignId=" + campaignId;
    }

    /** 수동 채점 — 설정을 바꾼 뒤 결과를 바로 보고 싶을 때 쓴다. */
    @PostMapping("/influencer/campaigns/{campaignId}/score")
    @Transactional
    public String score(@PathVariable UUID campaignId, RedirectAttributes redirectAttributes) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INFLUENCER_CAMPAIGN_NOT_FOUND, campaignId));

        List<InfluencerChannel> channels = channelRepository.findAll().stream()
                .filter(channel -> channel.getStatus() == ChannelStatus.ACTIVE)
                .toList();

        int scored = scoringService.scoreAll(campaign, channels);
        redirectAttributes.addFlashAttribute("flashSuccess",
                "%d개 채널을 채점했습니다.".formatted(scored));
        return "redirect:/influencer/grades?campaignId=" + campaignId;
    }
}
