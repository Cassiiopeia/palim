package kr.suhsaechan.palim.common.error;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * 에러 메시지를 로케일에 맞춰 조립한다.
 *
 * <p>메시지는 각 도메인 모듈의 {@code errors/{도메인}.properties} 에 있다. 예외가 문구를
 * 갖지 않는 이유가 여기 있다 — 문구를 코드에 박으면 다국어가 불가능하고 수정에 재배포가 필요하다.
 *
 * <p>키가 없으면 예외를 던지지 않고 {@link ErrorCode#name()} 을 반환한다. 메시지 누락 때문에
 * 응답 자체가 실패하는 것은 더 나쁘기 때문이다. 누락은 빌드 시 검증 테스트가 잡는다.
 */
@Component
@RequiredArgsConstructor
public class ErrorMessageResolver {

    private final MessageSource messageSource;

    /** 현재 요청의 로케일로 조립한다. */
    public String resolve(ErrorCode errorCode, Object... args) {
        return resolve(errorCode, LocaleContextHolder.getLocale(), args);
    }

    public String resolve(ErrorCode errorCode, Locale locale, Object... args) {
        return messageSource.getMessage(errorCode.messageKey(), args, errorCode.name(), locale);
    }

    /** 메시지가 정의되어 있는지. 누락 검증 테스트가 쓴다. */
    public boolean isDefined(ErrorCode errorCode, Locale locale) {
        String resolved = messageSource.getMessage(errorCode.messageKey(), null, null, locale);
        return resolved != null && !resolved.isBlank();
    }
}
