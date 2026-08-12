package kr.suhsaechan.palim.web.influencer;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.suhsaechan.palim.automation.influencer.domain.Campaign;
import kr.suhsaechan.palim.automation.influencer.domain.CampaignRepository;
import kr.suhsaechan.palim.automation.influencer.domain.CampaignStatus;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerScore;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerScoreRepository;
import kr.suhsaechan.palim.automation.influencer.rising.RisingSignalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 라이징 레이더 화면 (#43).
 *
 * <p>등급표가 "이 캠페인에 누가 맞는가"를 묻는다면, 레이더는 <b>"캠페인과 무관하게 지금 누가
 * 뜨고 있는가"</b>를 묻는다. 광고 단가는 구독자 수를 후행하고 조회수는 선행하므로, 조회수가
 * 먼저 터진 채널은 단가가 오르기 전의 차익 구간에 있다.
 *
 * <p>그래서 첫 숫자가 점수가 아니라 <b>차익배율</b>이다 — 지수 87점보다 "규모 대비 3.2배"가
 * 판단에 바로 쓰인다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class RisingRadarController {

    private static final int ROW_LIMIT = 100;

    private final RisingSignalService risingSignalService;
    private final CampaignRepository campaignRepository;
    private final InfluencerScoreRepository scoreRepository;
    private final Clock clock;

    @GetMapping("/influencer/rising")
    public String radar(Model model) {
        Instant now = Instant.now(clock);
        var signals = risingSignalService.findActive(ROW_LIMIT);

        // 진행 중인 캠페인에서 이미 채점된 채널인지 표시한다 — 레이더에서 발견한 채널을
        // 곧바로 등급표로 이어 보게 하는 연결이다.
        Set<UUID> scoredChannelIds = campaignRepository.findByStatus(CampaignStatus.ACTIVE).stream()
                .map(Campaign::getId)
                .flatMap(campaignId -> scoreRepository
                        .findByCampaignIdAndHardFailReasonIsNullOrderByTotalDesc(
                                campaignId, Limit.of(500))
                        .stream())
                .map(score -> score.getChannel().getId())
                .collect(Collectors.toSet());

        List<RisingRowView> rows = signals.stream()
                .map(signal -> RisingRowView.of(signal, now,
                        scoredChannelIds.contains(signal.getChannel().getId())))
                .toList();

        model.addAttribute("title", "라이징 레이더");
        model.addAttribute("rows", rows);
        model.addAttribute("freshCount", rows.stream().filter(RisingRowView::fresh).count());
        model.addAttribute("activeCampaigns",
                campaignRepository.findByStatus(CampaignStatus.ACTIVE));
        return "influencer/rising";
    }
}
