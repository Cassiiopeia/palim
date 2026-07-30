package kr.suhsaechan.palim.common.error;

import java.util.Map;
import lombok.Getter;

/**
 * 유일한 비즈니스 예외.
 *
 * <p>실패 유형마다 예외 클래스를 만들지 않는다. 구분은 {@link ErrorCode} 가 담당하므로,
 * 새로운 실패가 생겨도 <b>예외 클래스는 늘지 않는다.</b>
 *
 * <pre>{@code
 * throw new BusinessException(SkuErrorCode.SKU_NOT_FOUND, skuId);
 * }</pre>
 *
 * <p>메시지는 이 예외가 갖지 않는다. {@code messageArgs} 만 담고, 실제 문구는
 * {@link ErrorMessageResolver} 가 로케일에 맞춰 조립한다. 예외에 문구를 박으면 다국어가
 * 불가능하고 문구 수정에 재배포가 필요해진다.
 *
 * <p>{@code getMessage()} 는 로그용 최소 정보만 담는다. 사용자에게 보여주는 문구는 반드시
 * resolver 를 거쳐야 한다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object[] messageArgs;
    private final transient Map<String, Object> details;

    public BusinessException(ErrorCode errorCode, Object... messageArgs) {
        this(errorCode, null, Map.of(), messageArgs);
    }

    public BusinessException(ErrorCode errorCode, Throwable cause, Object... messageArgs) {
        this(errorCode, cause, Map.of(), messageArgs);
    }

    private BusinessException(ErrorCode errorCode, Throwable cause,
                              Map<String, Object> details, Object... messageArgs) {
        super(buildLogMessage(errorCode, messageArgs), cause);
        this.errorCode = errorCode;
        this.messageArgs = messageArgs != null ? messageArgs.clone() : new Object[0];
        this.details = details != null ? Map.copyOf(details) : Map.of();
    }

    /**
     * 구조화된 부가 정보를 붙인다.
     *
     * <p>필드별 검증 오류처럼 클라이언트가 프로그램적으로 다뤄야 하는 정보를 담는다.
     * 사람이 읽는 문구는 여기 넣지 않는다 — 그건 메시지 프로퍼티의 몫이다.
     */
    public BusinessException withDetails(Map<String, Object> details) {
        return new BusinessException(errorCode, getCause(), details, messageArgs);
    }

    public Object[] messageArgs() {
        return messageArgs.clone();
    }

    public boolean hasDetails() {
        return !details.isEmpty();
    }

    /** 해당 에러코드인지 확인한다. 흐름 제어에 쓴다(예 — 중복 수집 건너뛰기). */
    public boolean is(ErrorCode other) {
        return errorCode == other;
    }

    private static String buildLogMessage(ErrorCode errorCode, Object[] messageArgs) {
        if (errorCode == null) {
            return "알 수 없는 오류";
        }
        String base = "%s(%s)".formatted(errorCode.name(), errorCode.code());
        if (messageArgs == null || messageArgs.length == 0) {
            return base;
        }
        StringBuilder builder = new StringBuilder(base).append(" args=[");
        for (int i = 0; i < messageArgs.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(messageArgs[i]);
        }
        return builder.append(']').toString();
    }
}
