package kr.suhsaechan.palim.automation.influencer.youtube;

import java.time.Clock;
import java.time.LocalDate;
import kr.suhsaechan.palim.common.config.ConfigReader;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일일 할당량 관리.
 *
 * <p>호출 <b>전에</b> 예산을 확인하고 <b>호출 후에</b> 소모를 기록한다. 순서를 바꾸면 이미 쓴
 * 것을 나중에 알게 되어 초과를 막을 수 없다.
 *
 * <p>기록은 {@code REQUIRES_NEW} 다. 수집 트랜잭션이 롤백되어도 <b>API 는 이미 호출됐고 quota 는
 * 실제로 소모됐다.</b> 함께 롤백하면 원장이 실제보다 적게 남아 다음 호출에서 초과가 난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YoutubeQuotaService {

    private final YoutubeQuotaLedgerRepository ledgerRepository;
    private final ConfigReader config;
    private final Clock clock;

    /**
     * 예산 확인. 초과면 {@link ErrorCode#YOUTUBE_QUOTA_EXCEEDED} 를 던진다.
     *
     * <p>이 예외는 오류가 아니라 흐름 제어다 — 배치는 이걸 잡아 커서를 저장하고 정상 종료한다.
     */
    @Transactional(readOnly = true)
    public void ensureAvailable(int units, boolean search) {
        LocalDate today = LocalDate.now(clock);
        YoutubeQuotaLedger ledger = ledgerRepository.findByUsageDate(today).orElse(null);

        int used = ledger == null ? 0 : ledger.getUnitsUsed();
        int searchUsed = ledger == null ? 0 : ledger.getSearchUnits();

        int dailyLimit = config.getInt(YoutubeConfigKeys.QUOTA_DAILY_LIMIT);
        if (used + units > dailyLimit) {
            log.info("YouTube 일일 할당량 소진 — 사용 {}/{}, 요청 {}", used, dailyLimit, units);
            throw new BusinessException(ErrorCode.YOUTUBE_QUOTA_EXCEEDED, used, dailyLimit);
        }

        if (search) {
            int searchBudget = config.getInt(YoutubeConfigKeys.QUOTA_SEARCH_BUDGET);
            if (searchUsed + units > searchBudget) {
                log.info("YouTube 검색 예산 소진 — 사용 {}/{}", searchUsed, searchBudget);
                throw new BusinessException(ErrorCode.YOUTUBE_QUOTA_EXCEEDED, searchUsed, searchBudget);
            }
        }
    }

    /** 실제 소모 기록. 호출이 실패했더라도 quota 는 차감되므로 성공·실패와 무관하게 남긴다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(int units, boolean search) {
        LocalDate today = LocalDate.now(clock);
        YoutubeQuotaLedger ledger = ledgerRepository.findByUsageDate(today)
                .orElseGet(() -> ledgerRepository.save(YoutubeQuotaLedger.startOf(today)));
        ledger.consume(units, search);
        ledgerRepository.save(ledger);
    }

    /** 오늘 남은 예산. 배치가 "몇 건까지 처리할지" 정할 때 쓴다. */
    @Transactional(readOnly = true)
    public int remainingUnits() {
        int used = ledgerRepository.findByUsageDate(LocalDate.now(clock))
                .map(YoutubeQuotaLedger::getUnitsUsed)
                .orElse(0);
        return Math.max(0, config.getInt(YoutubeConfigKeys.QUOTA_DAILY_LIMIT) - used);
    }
}
