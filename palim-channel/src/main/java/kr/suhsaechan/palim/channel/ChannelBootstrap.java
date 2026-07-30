package kr.suhsaechan.palim.channel;

import java.util.EnumMap;
import java.util.Map;
import kr.suhsaechan.palim.common.ChannelCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채널 설정 초기화.
 *
 * <p>채널 7개와 재고 전송 설정을 기동 시 만든다. 모두 이미 있으면 건너뛰므로 재기동해도
 * 중복 생성되지 않는다.
 *
 * <p>채널은 전부 <b>비활성 상태로 등록</b>된다. 인증정보(P-02)가 등록되기 전에 수집이 시작되면
 * 인증 실패가 반복되어 채널 측 차단 위험이 있기 때문이다. 발주자가 인증정보를 넣은 뒤 웹에서
 * 활성화한다.
 *
 * <p>{@code ApplicationRunner} 를 쓴 이유는 초기화 실패 시 기동을 중단시키기 위함이다.
 * {@code ApplicationReadyEvent} 리스너에서 던진 예외는 로그만 남고 애플리케이션이 계속 떠서,
 * 설정이 없는 상태로 운영에 들어갈 수 있다.
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class ChannelBootstrap implements ApplicationRunner {

    /** 기능 명세서 F-01 이 정한 채널별 기본 수집 주기(초). */
    private static final Map<ChannelCode, Integer> DEFAULT_COLLECT_INTERVAL_SECONDS =
            new EnumMap<>(Map.of(
                    ChannelCode.COUPANG, 300,
                    ChannelCode.NAVER, 300,
                    ChannelCode.LOTTEON, 600,
                    ChannelCode.ELEVENST, 600,
                    ChannelCode.ESM, 600,
                    ChannelCode.SSG, 600,
                    // 공식 API 가 없어 엑셀 업로드로만 처리한다. 스케줄 수집 대상이 아니다.
                    ChannelCode.LOTTE_DEPT, 3600));

    private final ChannelService channelService;
    private final StockPushSettingService stockPushSettingService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        DEFAULT_COLLECT_INTERVAL_SECONDS.forEach((code, intervalSeconds) -> {
            Channel channel = channelService.registerIfAbsent(code, intervalSeconds);
            log.debug("채널 준비: {} (주기 {}초, 활성 {})",
                    channel.getCode(), channel.getCollectIntervalSeconds(), channel.isEnabled());
        });

        StockPushSetting pushSetting = stockPushSettingService.initializeIfAbsent();
        log.info("재고 전송 설정 준비 — 전송 {}, 시뮬레이션 {}, 변동량 상한 {}",
                pushSetting.isEnabled() ? "활성" : "비활성",
                pushSetting.isSimulationMode() ? "활성" : "비활성",
                pushSetting.getMaxDeltaPerPush());
    }
}
