package kr.suhsaechan.palim.connector.source.http;

import java.time.LocalDate;
import java.util.Map;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.springframework.util.StringUtils;

/**
 * 연결 검증 입력.
 *
 * <p>{@code secret} 을 나머지 값과 분리해 둔다. 인증키는 화면에 다시 표시하지 않고 로그에도
 * 남기지 않아야 하는데, 다른 설정과 한 덩어리로 다루면 어딘가에서 통째로 출력된다.
 *
 * @param preset  인증 흐름
 * @param sandbox 테스트 환경 여부. 테스트키와 정식키는 접속 주소가 다르다
 * @param params  비민감 설정 (회사코드·사용자ID·기준일 등)
 * @param secret  인증키·비밀번호. <b>로그·화면·에러 메시지에 넣지 않는다</b>
 */
public record ProbeRequest(ApiAuthPreset preset, boolean sandbox, Map<String, String> params,
                           String secret) {

    public ProbeRequest {
        // 프리셋 기본값(접속 주소 등) 위에 사용자 입력을 덮는다. 사용자는 계정 정보만 넣으면 되고,
        // 기본값이 맞지 않는 환경에서는 화면에서 덮어쓸 수 있다.
        params = preset.mergeDefaults(params == null ? Map.of() : params);
    }

    /** 필수 값이 없으면 호출 전에 막는다. 빈 값으로 요청하면 원인이 원격 서버 메시지로 흐려진다. */
    public String require(String key) {
        String value = params.get(key);
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.API_PROBE_INCOMPLETE, key);
        }
        return value.trim();
    }

    public String requireSecret() {
        if (!StringUtils.hasText(secret)) {
            throw new BusinessException(ErrorCode.API_PROBE_INCOMPLETE, "인증키");
        }
        return secret.trim();
    }

    /** 기준일. 지정하지 않으면 오늘로 본다. */
    public LocalDate baseDate() {
        String raw = params.get("baseDate");
        if (!StringUtils.hasText(raw)) {
            return LocalDate.now();
        }
        return LocalDate.parse(raw.trim());
    }
}
