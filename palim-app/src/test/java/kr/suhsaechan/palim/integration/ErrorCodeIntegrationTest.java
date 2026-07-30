package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kr.suhsaechan.palim.auth.AuthErrorCode;
import kr.suhsaechan.palim.channel.ChannelErrorCode;
import kr.suhsaechan.palim.common.error.CommonErrorCode;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.mapping.MappingErrorCode;
import kr.suhsaechan.palim.notification.NotificationErrorCode;
import kr.suhsaechan.palim.order.OrderErrorCode;
import kr.suhsaechan.palim.sku.SkuErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * ErrorCode 체계 검증.
 *
 * <p>에러코드가 도메인별로 흩어져 있는 구조의 대가는 "전체를 한눈에 보기 어렵다"는 점이다.
 * 그 대가를 이 테스트가 갚는다 — 코드 중복과 메시지 누락을 빌드 시점에 잡는다.
 *
 * <p><b>새 도메인의 ErrorCode 를 만들면 {@link #ALL_ERROR_CODE_TYPES} 에 추가해야 한다.</b>
 * 리플렉션으로 자동 수집하지 않는 이유는, 클래스패스 스캔이 조용히 실패하면 검증이 통과한 척
 * 하기 때문이다. 명시적 등록은 마찰이 있지만 빠뜨리면 드러난다.
 */
class ErrorCodeIntegrationTest extends IntegrationTest {

    private static final List<Class<? extends ErrorCode>> ALL_ERROR_CODE_TYPES = List.of(
            CommonErrorCode.class,
            SkuErrorCode.class,
            OrderErrorCode.class,
            MappingErrorCode.class,
            ChannelErrorCode.class,
            NotificationErrorCode.class,
            AuthErrorCode.class);

    @Autowired
    private MessageSource messageSource;

    private static List<ErrorCode> allErrorCodes() {
        List<ErrorCode> codes = new ArrayList<>();
        for (Class<? extends ErrorCode> type : ALL_ERROR_CODE_TYPES) {
            ErrorCode[] constants = type.getEnumConstants();
            assertThat(constants)
                    .as("%s 는 enum 이어야 한다", type.getSimpleName())
                    .isNotNull();
            codes.addAll(List.of(constants));
        }
        return codes;
    }

    @Test
    @DisplayName("등록된 ErrorCode 타입이 하나도 비어 있지 않다")
    void 에러코드가_수집된다() {
        assertThat(allErrorCodes()).isNotEmpty();
    }

    @Test
    @DisplayName("에러코드 문자열이 중복되지 않는다")
    void 코드가_중복되지_않는다() {
        Map<String, String> byCode = new HashMap<>();

        for (ErrorCode errorCode : allErrorCodes()) {
            String previous = byCode.put(errorCode.code(), errorCode.name());
            assertThat(previous)
                    .as("코드 %s 가 %s 와 %s 에서 중복된다", errorCode.code(), previous, errorCode.name())
                    .isNull();
        }
    }

    @Test
    @DisplayName("ErrorCode 이름이 중복되지 않는다 — 클라이언트가 이 값으로 분기한다")
    void 이름이_중복되지_않는다() {
        Map<String, String> byName = new HashMap<>();

        for (ErrorCode errorCode : allErrorCodes()) {
            String previous = byName.put(errorCode.name(), errorCode.code());
            assertThat(previous)
                    .as("이름 %s 가 코드 %s 와 %s 에서 중복된다",
                            errorCode.name(), previous, errorCode.code())
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
    @DisplayName("로케일에 따라 메시지가 달라진다")
    void 로케일별로_메시지가_다르다() {
        String korean = messageSource.getMessage(
                SkuErrorCode.SKU_NOT_FOUND.messageKey(), new Object[]{"SKU-001"}, Locale.KOREAN);
        String english = messageSource.getMessage(
                SkuErrorCode.SKU_NOT_FOUND.messageKey(), new Object[]{"SKU-001"}, Locale.ENGLISH);

        assertThat(korean).contains("SKU-001").isNotEqualTo(english);
        assertThat(english).contains("SKU-001").containsIgnoringCase("not found");
    }

    @Test
    @DisplayName("중복 수집은 로그를 남기지 않는 수준이다 — 정상 흐름 제어이기 때문")
    void 중복_수집은_디버그_수준이다() {
        assertThat(OrderErrorCode.ORDER_LINE_DUPLICATE.logLevel().name()).isEqualTo("DEBUG");
    }

    private void assertMessagesDefined(Locale locale) {
        List<String> missing = new ArrayList<>();

        for (ErrorCode errorCode : allErrorCodes()) {
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
