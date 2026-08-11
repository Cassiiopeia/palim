package kr.suhsaechan.palim.automation.influencer.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import kr.suhsaechan.palim.common.config.ConfigReader;

/**
 * AI 호출 사용량 제한.
 *
 * <p>OpenAI 는 실제로 과금된다. 사용량이 예상을 넘는 흔한 경로는 셋이다 — <b>버튼 연타</b>,
 * <b>실패 후 재시도 루프</b>, <b>다른 서비스와의 키 공유</b>. 셋 다 코드가 정상 동작하는 상태에서
 * 벌어지므로 예외 처리로는 막히지 않는다.
 *
 * <p>그래서 두 겹으로 막는다.
 *
 * <ol>
 *   <li><b>쿨다운(캐시)</b> — 같은 작업을 N초 안에 다시 실행하지 못한다. 연타·중복 실행을
 *       사람이 눈치채기 전에 막는 층이다. 짧은 주기의 판단이라 메모리로 충분하다</li>
 *   <li><b>일일 상한(DB)</b> — 하루 호출 수의 절대 한도. 무한 루프가 돌아도 이 선에서 멈춘다.
 *       프로세스가 재시작되면 메모리 카운터는 0 이 되므로 <b>이 층만은 DB 여야</b> 한다</li>
 * </ol>
 *
 * <p>캐시에 담는 값은 boolean 이 아니라 <b>마지막 실행 시각</b>이다. TTL 로 만료를 표현하면
 * 쿨다운을 바꿀 때 캐시를 다시 만들어야 하지만, 시각을 담아 두고 매번 비교하면 설정 변경이
 * 그 즉시 반영된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiCallGuard {

    /** 쿨다운 기록 캐시 이름. */
    public static final String COOLDOWN_CACHE = "aiCallCooldown";

    private final CacheManager cacheManager;
    private final AiCallLedgerRepository ledgerRepository;
    private final ConfigReader config;
    private final Clock clock;

    /**
     * 작업 실행 가능 여부 확인 — 통과하면 쿨다운 시계를 다시 시작한다.
     *
     * @param taskKey 쿨다운 단위. 캠페인별로 걸어야 한 캠페인의 연타가 다른 캠페인을 막지 않는다
     * @throws BusinessException 쿨다운 중이거나 일일 상한을 넘었을 때
     */
    public void ensureAllowed(String taskKey) {
        Duration cooldown = Duration.ofSeconds(config.getInt(AiConfigKeys.COOLDOWN_SECONDS));
        Cache cache = cacheManager.getCache(COOLDOWN_CACHE);

        if (cache != null && cooldown.isPositive()) {
            Instant last = cache.get(taskKey, Instant.class);
            Instant now = Instant.now(clock);

            if (last != null) {
                Duration elapsed = Duration.between(last, now);
                if (elapsed.compareTo(cooldown) < 0) {
                    long remaining = cooldown.minus(elapsed).toSeconds() + 1;
                    log.info("AI 호출 쿨다운 — {} 초 후 다시 시도 가능 ({})", remaining, taskKey);
                    throw new BusinessException(ErrorCode.AI_RATE_LIMITED, remaining);
                }
            }
            cache.put(taskKey, now);
        }

        ensureDailyBudget();
    }

    /**
     * 호출 1건 기록.
     *
     * <p>{@code REQUIRES_NEW} 다. 심사 트랜잭션이 롤백되어도 <b>API 는 이미 호출됐고 요금은
     * 발생했다.</b> 함께 롤백하면 원장이 실제보다 적게 남아 상한이 헐거워진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record() {
        LocalDate today = today();
        AiCallLedger ledger = ledgerRepository.findByUsageDate(today)
                .orElseGet(() -> ledgerRepository.save(AiCallLedger.startOf(today)));
        ledger.increase();
        ledgerRepository.save(ledger);
    }

    /** 호출 직전 확인 — 상한에 닿으면 그 자리에서 멈춘다. */
    @Transactional(readOnly = true)
    public void ensureDailyBudget() {
        int limit = config.getInt(AiConfigKeys.DAILY_CALL_LIMIT);
        int used = usedToday();

        if (used >= limit) {
            log.warn("AI 일일 호출 상한 도달 — {}/{}", used, limit);
            throw new BusinessException(ErrorCode.AI_DAILY_LIMIT_EXCEEDED, used, limit);
        }
    }

    @Transactional(readOnly = true)
    public int usedToday() {
        return ledgerRepository.findByUsageDate(today())
                .map(AiCallLedger::getCallCount)
                .orElse(0);
    }

    @Transactional(readOnly = true)
    public int remainingToday() {
        return Math.max(0, config.getInt(AiConfigKeys.DAILY_CALL_LIMIT) - usedToday());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }
}
