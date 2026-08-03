package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.context.MessageSource;

/**
 * ErrorCode 체계 검증.
 *
 * <p>모든 코드가 한 enum 에 모여 있으므로 {@code values()} 로 <b>자동 순회</b>한다. 새 코드를
 * 추가하면 이 테스트가 자동으로 검사 대상에 포함하므로, 검증 목록을 손으로 관리할 필요가 없다.
 * 도메인별 구현체로 나눴다면 클래스를 손으로 등록해야 하고 빠뜨리면 검증에 구멍이 생긴다.
 */
class ErrorCodeIntegrationTest extends IntegrationTest {

    @Autowired
    private MessageSource messageSource;

    @Test
    @DisplayName("에러코드 문자열이 중복되지 않는다")
    void 코드가_중복되지_않는다() {
        Map<String, String> byCode = new HashMap<>();

        for (ErrorCode errorCode : ErrorCode.values()) {
            String previous = byCode.put(errorCode.code(), errorCode.name());
            assertThat(previous)
                    .as("코드 %s 가 %s 와 %s 에서 중복된다", errorCode.code(), previous, errorCode.name())
                    .isNull();
        }
    }

    @Test
    @DisplayName("모든 ErrorCode 에 한글 메시지가 정의되어 있다")
    void 한글_메시지가_모두_있다() {
        assertMessagesDefined(Locale.KOREAN);
    }

    @Test
    @DisplayName("모든 ErrorCode 에 영문 메시지가 정의되어 있다")
    void 영문_메시지가_모두_있다() {
        assertMessagesDefined(Locale.ENGLISH);
    }

    @Test
    @DisplayName("모든 ErrorCode 에 HTTP 상태와 로그 레벨이 지정되어 있다")
    void 상태와_로그레벨이_모두_있다() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertThat(errorCode.httpStatus()).as("%s 의 HTTP 상태", errorCode.name()).isNotNull();
            assertThat(errorCode.logLevel()).as("%s 의 로그 레벨", errorCode.name()).isNotNull();
        }
    }

    @Test
    @DisplayName("코드 접두사가 정의된 도메인 문자 중 하나다")
    void 접두사_규칙을_지킨다() {
        // C 공통 / S 재고 / O 주문 / M 매핑 / H 채널 / N 알림 / A 인증 / I 인시던트
        List<Character> allowed = List.of('C', 'S', 'O', 'M', 'H', 'N', 'A', 'I');

        for (ErrorCode errorCode : ErrorCode.values()) {
            assertThat(errorCode.code())
                    .as("%s 의 코드 형식", errorCode.name())
                    .matches("^[A-Z]\\d{3}$");
            assertThat(allowed)
                    .as("%s 의 접두사 %s", errorCode.name(), errorCode.code().charAt(0))
                    .contains(errorCode.code().charAt(0));
        }
    }

    @Test
    @DisplayName("500 응답은 ERROR 수준으로 로깅한다 — 조용히 넘어가면 안 된다")
    void 서버_오류는_ERROR_수준이다() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            if (errorCode.httpStatus().is5xxServerError()) {
                assertThat(errorCode.logLevel())
                        .as("%s 는 5xx 이므로 ERROR 로 로깅해야 한다", errorCode.name())
                        .isIn(LogLevel.ERROR, LogLevel.FATAL, LogLevel.DEBUG);
            }
        }
    }

    @Test
    @DisplayName("로케일에 따라 메시지가 달라진다")
    void 로케일별로_메시지가_다르다() {
        String korean = messageSource.getMessage(
                ErrorCode.SKU_NOT_FOUND.messageKey(), new Object[]{"SKU-001"}, Locale.KOREAN);
        String english = messageSource.getMessage(
                ErrorCode.SKU_NOT_FOUND.messageKey(), new Object[]{"SKU-001"}, Locale.ENGLISH);

        assertThat(korean).contains("SKU-001").isNotEqualTo(english);
        assertThat(english).contains("SKU-001").containsIgnoringCase("not found");
    }

    /**
     * 중복 수집은 정상 흐름 제어이므로 경고로 남으면 안 된다.
     *
     * <p>수집 커서가 구간을 겹쳐 조회하는 구조에서 같은 주문이 반복 수집되는 것이 정상이다.
     * 이를 WARN 으로 두면 정상 동작이 경고 로그를 가득 채운다.
     */
    @Test
    @DisplayName("중복 수집은 DEBUG 수준으로 로깅한다")
    void 중복_수집은_디버그_수준이다() {
        assertThat(ErrorCode.ORDER_LINE_DUPLICATE.logLevel()).isEqualTo(LogLevel.DEBUG);
    }

    private void assertMessagesDefined(Locale locale) {
        List<String> missing = new ArrayList<>();

        for (ErrorCode errorCode : ErrorCode.values()) {
            String key = errorCode.messageKey();
            // use-code-as-default-message 가 켜져 있어 누락 시 키 자체가 반환된다.
            String resolved = messageSource.getMessage(key, null, key, locale);
            if (resolved == null || resolved.isBlank() || resolved.equals(key)) {
                missing.add("%s (%s)".formatted(errorCode.name(), key));
            }
        }

        assertThat(missing)
                .as("%s 메시지가 누락된 ErrorCode", locale)
                .isEmpty();
    }
}
