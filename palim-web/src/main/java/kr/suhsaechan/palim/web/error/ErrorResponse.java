package kr.suhsaechan.palim.web.error;

import java.time.Instant;
import java.util.Map;
import kr.suhsaechan.palim.common.error.ErrorCode;

/**
 * 오류 응답 형식.
 *
 * <p><b>클라이언트는 {@code errorCode} 로 분기한다.</b> 메시지 문자열로 판단하면 문구가 바뀔 때
 * 깨지고, 다국어 환경에서는 아예 불가능하다.
 *
 * <p>{@code errorMessage} 는 표시용 보조 수단이다. 화면에서 언어를 전환한다면 클라이언트가
 * {@code errorCode} 로 자체 메시지를 선택하고, 그렇지 않으면 이 값을 그대로 쓴다.
 *
 * @param errorCode    실패 유형 식별자. 예 {@code SKU_NOT_FOUND}
 * @param code         숫자 코드. 예 {@code S001}
 * @param errorMessage 로케일에 맞춰 조립된 문구
 * @param status       HTTP 상태
 * @param path         요청 경로
 * @param timestamp    발생 시각
 * @param details      필드별 검증 오류 같은 구조화된 부가 정보. 없으면 null
 */
public record ErrorResponse(
        String errorCode,
        String code,
        String errorMessage,
        int status,
        String path,
        Instant timestamp,
        Map<String, Object> details
) {

    public static ErrorResponse of(ErrorCode errorCode, String errorMessage, String path) {
        return of(errorCode, errorMessage, path, null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String errorMessage, String path,
                                   Map<String, Object> details) {
        return new ErrorResponse(
                errorCode.name(),
                errorCode.code(),
                errorMessage,
                errorCode.httpStatus().value(),
                path,
                Instant.now(),
                details == null || details.isEmpty() ? null : Map.copyOf(details));
    }
}
