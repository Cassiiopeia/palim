package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.suhsaechan.palim.automation.influencer.ai.AiCallGuard;
import kr.suhsaechan.palim.automation.influencer.ai.AiConfigKeys;
import kr.suhsaechan.palim.common.config.SystemConfigService;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * AI 사용량 제한 검증.
 *
 * <p>OpenAI 는 실제로 과금되므로 이 두 층이 뚫리면 그대로 비용이 된다.
 */
class AiCallGuardIntegrationTest extends IntegrationTest {

    @Autowired
    private AiCallGuard aiCallGuard;

    @Autowired
    private SystemConfigService systemConfigService;

    @Test
    @DisplayName("쿨다운 중에는 같은 작업을 다시 실행할 수 없다 — 버튼 연타 방지")
    void 쿨다운() {
        systemConfigService.update(AiConfigKeys.COOLDOWN_SECONDS, "60", "test");
        String key = "campaign:cooldown-test";

        aiCallGuard.ensureAllowed(key);

        assertThatThrownBy(() -> aiCallGuard.ensureAllowed(key))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_RATE_LIMITED);
    }

    @Test
    @DisplayName("작업 키가 다르면 서로 막지 않는다 — 한 캠페인이 다른 캠페인을 멈추면 안 된다")
    void 키별_독립() {
        systemConfigService.update(AiConfigKeys.COOLDOWN_SECONDS, "60", "test");

        aiCallGuard.ensureAllowed("campaign:independent-a");

        // 다른 키는 그대로 통과한다
        aiCallGuard.ensureAllowed("campaign:independent-b");
    }

    @Test
    @DisplayName("쿨다운을 0으로 두면 걸리지 않는다")
    void 쿨다운_해제() {
        systemConfigService.update(AiConfigKeys.COOLDOWN_SECONDS, "0", "test");
        String key = "campaign:no-cooldown";

        aiCallGuard.ensureAllowed(key);
        aiCallGuard.ensureAllowed(key);
    }

    @Test
    @DisplayName("일일 상한에 닿으면 호출을 거부한다 — 비용의 마지막 방어선")
    void 일일_상한() {
        systemConfigService.update(AiConfigKeys.DAILY_CALL_LIMIT, "2", "test");
        int before = aiCallGuard.usedToday();

        aiCallGuard.record();
        aiCallGuard.record();

        assertThat(aiCallGuard.usedToday()).isEqualTo(before + 2);
        assertThatThrownBy(() -> aiCallGuard.ensureDailyBudget())
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_DAILY_LIMIT_EXCEEDED);

        // 다른 테스트에 영향을 주지 않도록 되돌린다
        systemConfigService.update(AiConfigKeys.DAILY_CALL_LIMIT, "200", "test");
    }

    @Test
    @DisplayName("남은 한도는 상한에서 사용량을 뺀 값이다 — 화면이 이 값을 보여준다")
    void 잔여_한도() {
        systemConfigService.update(AiConfigKeys.DAILY_CALL_LIMIT, "100", "test");

        int used = aiCallGuard.usedToday();

        assertThat(aiCallGuard.remainingToday()).isEqualTo(100 - used);
    }
}
