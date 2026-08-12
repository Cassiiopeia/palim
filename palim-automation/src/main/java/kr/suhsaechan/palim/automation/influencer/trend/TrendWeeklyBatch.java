package kr.suhsaechan.palim.automation.influencer.trend;

import java.util.List;
import kr.suhsaechan.palim.automation.influencer.discover.DiscoveryCursor;
import kr.suhsaechan.palim.automation.influencer.discover.DiscoveryCursorRepository;
import kr.suhsaechan.palim.automation.influencer.domain.DiscoverySource;
import kr.suhsaechan.palim.common.config.ConfigReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주간 트렌드 집계와 발굴 시드 환류.
 *
 * <p><b>환류가 이 기능의 핵심이다.</b> 트렌드 보드만 있으면 사람이 보고 키워드를 손으로 옮겨야
 * 하지만, 급상승 키워드를 발굴 시드에 자동으로 넣으면 다음 흐름이 스스로 돈다:
 *
 * <pre>
 *   새 트렌드 등장 → 그 키워드로 채널 검색 → 신규 채널 수집·채점 → 라이징 감지 → 주간 알림
 * </pre>
 *
 * <p>"트렌드에 민감해야 한다"는 요구는 결국 이 루프를 말한다. 사람이 매주 키워드를 관리하는
 * 방식은 몇 주 지나면 하지 않게 된다.
 *
 * <p>월요일 새벽 4시 — 야간 배치(3시)가 끝난 뒤이고, 라이징 주간 알림(9시)보다 앞이다.
 * 순서가 중요하다: 집계 → 시드 추가 → (다음 야간 배치가) 그 시드로 발굴.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrendWeeklyBatch {

    private final TrendAggregationService trendAggregationService;
    private final DiscoveryCursorRepository cursorRepository;
    private final ConfigReader config;

    @Scheduled(cron = "0 0 4 * * MON", zone = "Asia/Seoul")
    @Transactional
    public void run() {
        if (!config.getBoolean(TrendConfigKeys.ENABLED)) {
            log.debug("트렌드 주간 집계 — 사용 안 함");
            return;
        }

        int saved = trendAggregationService.aggregateLastWeek();
        if (saved == 0) {
            return;
        }

        if (config.getBoolean(TrendConfigKeys.SEED_FEEDBACK_ENABLED)) {
            feedbackSeeds();
        }
    }

    /**
     * 급상승 키워드를 발굴 시드로 추가한다.
     *
     * <p>기존 커서를 건드리지 않는다 — 이미 있는 키워드는 진행 위치가 남아 있고, 여기서
     * 덮어쓰면 순회가 처음으로 되돌아간다.
     *
     * <p>전체 집계({@code _all})에서 뽑는다. 카테고리별로 뽑으면 카테고리 수만큼 시드가 늘어
     * 검색 예산을 한 번에 다 쓴다.
     */
    private void feedbackSeeds() {
        int limit = config.getInt(TrendConfigKeys.SEED_FEEDBACK_LIMIT);
        if (limit <= 0) {
            return;
        }

        List<TrendKeyword> rising = trendAggregationService.findRising(
                TrendKeyword.ALL_CATEGORIES, limit);

        int added = 0;
        for (TrendKeyword keyword : rising) {
            boolean exists = cursorRepository.findBySourceAndCursorKey(
                    DiscoverySource.KEYWORD_SEARCH, keyword.getKeyword()).isPresent();
            if (exists) {
                continue;
            }
            cursorRepository.save(DiscoveryCursor.of(DiscoverySource.KEYWORD_SEARCH,
                    keyword.getKeyword()));
            added++;
        }

        log.info("트렌드 시드 환류 — 급상승 {}건 중 신규 {}건 추가", rising.size(), added);
    }
}
