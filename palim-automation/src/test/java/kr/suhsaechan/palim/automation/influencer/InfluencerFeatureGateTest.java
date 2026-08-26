package kr.suhsaechan.palim.automation.influencer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import kr.suhsaechan.palim.automation.influencer.batch.InfluencerBatchConfigDefinitions;
import kr.suhsaechan.palim.automation.influencer.batch.InfluencerBatchConfigKeys;
import kr.suhsaechan.palim.automation.influencer.batch.InfluencerNightlyBatch;
import kr.suhsaechan.palim.automation.influencer.collect.ChannelCollectService;
import kr.suhsaechan.palim.automation.influencer.discover.DiscoveryCursorRepository;
import kr.suhsaechan.palim.automation.influencer.discover.DiscoveryService;
import kr.suhsaechan.palim.automation.influencer.domain.CampaignRepository;
import kr.suhsaechan.palim.automation.influencer.domain.InfluencerChannelRepository;
import kr.suhsaechan.palim.automation.influencer.rising.RisingConfigDefinitions;
import kr.suhsaechan.palim.automation.influencer.rising.RisingConfigKeys;
import kr.suhsaechan.palim.automation.influencer.rising.RisingSignalService;
import kr.suhsaechan.palim.automation.influencer.rising.RisingWeeklyNotifier;
import kr.suhsaechan.palim.automation.influencer.score.ScoringService;
import kr.suhsaechan.palim.automation.influencer.trend.TrendAggregationService;
import kr.suhsaechan.palim.automation.influencer.trend.TrendConfigDefinitions;
import kr.suhsaechan.palim.automation.influencer.trend.TrendConfigKeys;
import kr.suhsaechan.palim.automation.influencer.trend.TrendWeeklyBatch;
import kr.suhsaechan.palim.automation.influencer.youtube.YoutubeQuotaService;
import kr.suhsaechan.palim.common.config.ConfigDefinition;
import kr.suhsaechan.palim.common.config.InMemoryConfigReader;
import kr.suhsaechan.palim.notification.OutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 기능을 끄면 <b>저절로 도는 것</b>이 멈추는가.
 *
 * <p>스케줄러는 「안 돌았음」 을 통합 시험으로 증명하기 어렵다 — 그냥 시각이 안 됐을 수도 있다.
 * 그래서 DB 없이 진입 지점만 못 박는다. 나중에 누가 가드를 아래로 옮기거나 지우면 여기서 바로
 * 드러난다.
 *
 * <p>멈춰야 하는 이유가 셋이다. 야간 수집은 <b>하루치 외부 할당량</b>을 태우고, 트렌드 집계는
 * 급상승 낱말을 되먹여 <b>다음 수집 대상을 스스로 늘리며</b>, 주간 알림은 화면이 없는데도
 * 메시지를 보낸다.
 */
class InfluencerFeatureGateTest {

    /** 마스터만 끈 상태. 하위 설정은 저마다의 기본값 그대로다. */
    private static InfluencerFeature off() {
        return feature("false");
    }

    /** 마스터는 켜고 하위를 끈 상태. 기존 운영 선택지가 살아 있는지 본다. */
    private static InfluencerFeature on() {
        return feature("true");
    }

    private static InfluencerFeature feature(String enabled) {
        return new InfluencerFeature(defaults().with(InfluencerFeature.ENABLED, enabled));
    }

    /** 인플루언서 설정 정의 전부. */
    private static InMemoryConfigReader defaults() {
        List<ConfigDefinition> definitions = new ArrayList<>();
        definitions.addAll(new InfluencerFeatureConfigDefinitions().definitions());
        definitions.addAll(new InfluencerBatchConfigDefinitions().definitions());
        definitions.addAll(new TrendConfigDefinitions().definitions());
        definitions.addAll(new RisingConfigDefinitions().definitions());
        return InMemoryConfigReader.ofDefaults(definitions);
    }

    /**
     * 하위 설정을 <b>켜 둔</b> 읽기.
     *
     * <p>스케줄러에 빈 설정을 넘기면 하위 가드가 어차피 막아, 마스터를 지워도 시험이 통과한다.
     * 하위를 켜 두어야 <b>멈추는 이유가 마스터 하나뿐</b>임이 분명해진다.
     */
    private static InMemoryConfigReader subFlagsOn() {
        return defaults()
                .with(InfluencerBatchConfigKeys.ENABLED, "true")
                .with(TrendConfigKeys.ENABLED, "true")
                .with(RisingConfigKeys.WEEKLY_NOTIFICATION_ENABLED, "true");
    }

    @Test
    @DisplayName("꺼져 있으면 야간 수집이 아무것도 부르지 않는다")
    void nightlyBatchStaysQuiet() {
        DiscoveryService discovery = mock(DiscoveryService.class);
        ChannelCollectService collect = mock(ChannelCollectService.class);
        ScoringService scoring = mock(ScoringService.class);
        InfluencerChannelRepository channels = mock(InfluencerChannelRepository.class);
        CampaignRepository campaigns = mock(CampaignRepository.class);
        YoutubeQuotaService quota = mock(YoutubeQuotaService.class);

        new InfluencerNightlyBatch(discovery, collect, scoring, channels, campaigns, quota,
                subFlagsOn(), off(), Clock.systemUTC()).run();

        // 할당량 조회조차 하지 않아야 한다 — 켜져 있으면 로그를 찍으려고 먼저 부른다.
        verifyNoInteractions(discovery, collect, scoring, channels, campaigns, quota);
    }

    @Test
    @DisplayName("꺼져 있으면 트렌드 집계가 아무것도 부르지 않는다")
    void trendBatchStaysQuiet() {
        TrendAggregationService aggregation = mock(TrendAggregationService.class);
        DiscoveryCursorRepository cursors = mock(DiscoveryCursorRepository.class);

        new TrendWeeklyBatch(aggregation, cursors, subFlagsOn(), off()).run();

        verifyNoInteractions(aggregation, cursors);
    }

    @Test
    @DisplayName("꺼져 있으면 주간 알림이 나가지 않는다")
    void weeklyNotifierStaysQuiet() {
        RisingSignalService signals = mock(RisingSignalService.class);
        OutboxService outbox = mock(OutboxService.class);

        new RisingWeeklyNotifier(signals, outbox, subFlagsOn(), off(), Clock.systemUTC())
                .notifyWeekly();

        verifyNoInteractions(signals, outbox);
    }

    /**
     * 마스터를 켜도 하위 설정이 꺼져 있으면 그대로 멈춘다.
     *
     * <p>마스터 가드를 기존 가드 <b>앞</b>에 넣었을 뿐, 「기능은 쓰되 이 배치만 끈다」 는 기존
     * 운영 선택지를 없앤 것이 아님을 확인한다.
     */
    @Test
    @DisplayName("켜도 하위 설정이 꺼져 있으면 여전히 멈춘다")
    void subFlagStillWins() {
        TrendAggregationService aggregation = mock(TrendAggregationService.class);
        DiscoveryCursorRepository cursors = mock(DiscoveryCursorRepository.class);

        InMemoryConfigReader subOff = defaults().with(TrendConfigKeys.ENABLED, "false");

        new TrendWeeklyBatch(aggregation, cursors, subOff, on()).run();

        verifyNoInteractions(aggregation, cursors);
    }
}
