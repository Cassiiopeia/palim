package kr.suhsaechan.palim.web.monitor;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import kr.suhsaechan.palim.channel.ChannelService;
import kr.suhsaechan.palim.collector.CollectProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 수집 상태 모니터 (#30).
 *
 * <p>"수집이 며칠째 멈췄는데 아무도 모름"이 이 시스템 최악의 장애다. 수집이 멈추면 재고
 * 차감이 끊기고 그 상태로 판매가 계속되면 오버셀이 쌓인다. 텔레그램 알림을 놓쳐도 여기서
 * 확인할 수 있어야 한다.
 *
 * <p>조치 필요 상태를 목록 앞으로 정렬한다 — 발주자는 첫 화면에서 문제를 봐야 한다.
 */
@Controller
@RequiredArgsConstructor
public class CollectMonitorController {

    private final ChannelService channelService;
    private final CollectProperties collectProperties;

    @GetMapping("/monitor/collect")
    public String view(Model model) {
        Instant now = Instant.now();
        int threshold = collectProperties.failureThreshold();

        List<CollectStatusView> statuses = channelService.findAll().stream()
                .map(channel -> CollectStatusView.of(channel, threshold, now))
                .sorted(Comparator
                        .comparing((CollectStatusView status) -> !status.health().needsAttention())
                        .thenComparing(status -> status.code().ordinal()))
                .toList();

        long attentionCount = statuses.stream()
                .filter(status -> status.health().needsAttention())
                .count();

        model.addAttribute("title", "수집 모니터");
        model.addAttribute("statuses", statuses);
        model.addAttribute("attentionCount", attentionCount);
        return "monitor/collect";
    }
}
