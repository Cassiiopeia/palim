package kr.suhsaechan.palim.automation.influencer.rising;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannel;
import kr.suhsaechan.palim.automation.influencer.domain.RefreshTier;
import kr.suhsaechan.palim.automation.influencer.scoring.ChannelMetrics;
import kr.suhsaechan.palim.automation.influencer.scoring.RisingIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 라이징 신호 기록·해제.
 *
 * <p>채점할 때마다 호출되어 지수를 신호 테이블에 반영한다. 지수 계산 자체는 스코어링 엔진이
 * 하고 여기서는 <b>상태 전이</b>만 다룬다 — 새로 감지했는지, 유지 중인지, 꺾였는지.
 *
 * <p>감지되면 채널 갱신 티어를 {@link RefreshTier#RISING}(매일)으로 올린다. 성장 곡선은 하루
 * 단위로 봐야 의미가 있고, 며칠만 늦어도 단가가 오르기 때문이다. 해제되면 {@code WARM} 으로
 * 되돌려 할당량을 아낀다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RisingSignalService {

    private final RisingSignalRepository signalRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 채점 결과를 신호로 반영한다.
     *
     * @param risingBadge 지수가 임계 이상인가 — 판정은 스코어링 엔진이 이미 했다
     */
    @Transactional
    public void apply(InfluencerChannel channel, RisingIndex index, ChannelMetrics metrics,
                      boolean risingBadge) {
        Instant now = Instant.now(clock);
        RisingSignal existing = signalRepository.findByChannelId(channel.getId()).orElse(null);

        if (!risingBadge) {
            if (existing != null && existing.isActive()) {
                log.info("라이징 해제 — {} (지수 {})", channel.getYoutubeChannelId(), index.total());
                existing.deactivate(now);
                // 매일 보던 채널을 평상 주기로 되돌린다.
                if (channel.getRefreshTier() == RefreshTier.RISING) {
                    channel.changeTier(RefreshTier.WARM);
                }
            }
            return;
        }

        BigDecimal total = round(index.total(), 2);
        BigDecimal arbitrage = round(
                ArbitrageRatio.of(channel.getSubscriberCount(), metrics.medianViews()), 3);
        long medianViews = Math.round(metrics.medianViews());
        String breakdown = objectMapper.writeValueAsString(index.breakdown());

        if (existing == null) {
            signalRepository.save(RisingSignal.detect(channel, total, breakdown, arbitrage,
                    medianViews, now));
            log.info("라이징 감지 — {} (지수 {}, 차익배율 {})",
                    channel.getYoutubeChannelId(), total, arbitrage);
        } else {
            existing.refresh(total, breakdown, arbitrage, medianViews, now);
        }
        channel.changeTier(RefreshTier.RISING);
    }

    @Transactional(readOnly = true)
    public List<RisingSignal> findActive(int limit) {
        return signalRepository.findByActiveTrueOrderByTotalDesc(Limit.of(limit));
    }

    /** 알림용 — 기준 시각 이후 새로 감지된 것만. 이미 알린 채널을 반복해 보내지 않는다. */
    @Transactional(readOnly = true)
    public List<RisingSignal> findDetectedAfter(Instant since, int limit) {
        return signalRepository.findByActiveTrueAndDetectedAtAfterOrderByTotalDesc(
                since, Limit.of(limit));
    }

    @Transactional(readOnly = true)
    public long activeCount() {
        return signalRepository.countByActiveTrue();
    }

    private static BigDecimal round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }
}
