package kr.suhsaechan.palim.channel;

import kr.suhsaechan.palim.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채널 재고 전송 안전장치 설정 서비스 (F-08).
 */
@Service
@RequiredArgsConstructor
public class StockPushSettingService {

    private final StockPushSettingRepository stockPushSettingRepository;

    /**
     * 설정이 없으면 안전한 기본값으로 만든다.
     *
     * <p>부트스트랩에서 호출한다. 전송 비활성 + 시뮬레이션 활성으로 시작하는 이유는, 기본값이
     * 안전한 쪽이어야 하기 때문이다. 재고 계산 오류로 0을 전송하면 상품이 전 채널에서 품절
     * 처리되어 매출 손실이 발생한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockPushSetting initializeIfAbsent() {
        return stockPushSettingRepository.findFirstByOrderByCreatedAtAsc()
                .orElseGet(() -> stockPushSettingRepository.save(StockPushSetting.createDefault()));
    }

    @Transactional(readOnly = true)
    public StockPushSetting get() {
        return stockPushSettingRepository.findFirstByOrderByCreatedAtAsc()
                .orElseThrow(() -> new BusinessException(
                        ChannelErrorCode.STOCK_PUSH_SETTING_NOT_INITIALIZED));
    }

    /** 전체 중단 스위치. 사고 발생 시 가장 먼저 눌러야 하는 스위치다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void disablePush() {
        get().disable();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enablePush() {
        get().enable();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void turnOnSimulation() {
        get().turnOnSimulation();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void turnOffSimulation() {
        get().turnOffSimulation();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changeMaxDeltaPerPush(int maxDelta) {
        get().changeMaxDeltaPerPush(maxDelta);
    }
}
