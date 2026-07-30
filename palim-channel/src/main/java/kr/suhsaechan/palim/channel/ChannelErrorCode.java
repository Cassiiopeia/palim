package kr.suhsaechan.palim.channel;

import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.logging.LogLevel;
import org.springframework.http.HttpStatus;

/**
 * 채널 도메인 실패 (접두사 {@code H}).
 */
@Getter
@RequiredArgsConstructor
public enum ChannelErrorCode implements ErrorCode {

    CHANNEL_NOT_FOUND("H001", HttpStatus.NOT_FOUND, LogLevel.WARN),

    CHANNEL_CREDENTIAL_NOT_FOUND("H002", HttpStatus.NOT_FOUND, LogLevel.WARN),

    CREDENTIAL_ENCRYPT_FAILED("H003", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    /**
     * 복호화 실패 — 마스터키가 교체되었거나 암호문이 손상됐다.
     *
     * <p>해당 채널의 수집이 즉시 중단되므로 텔레그램 경고 대상이다.
     */
    CREDENTIAL_DECRYPT_FAILED("H004", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    /** 부트스트랩이 수행되지 않았다. 설정 없이 운영에 들어간 상태이므로 즉시 조치해야 한다. */
    STOCK_PUSH_SETTING_NOT_INITIALIZED("H005", HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR),

    INVALID_COLLECT_INTERVAL("H006", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    INVALID_MAX_DELTA("H007", HttpStatus.BAD_REQUEST, LogLevel.DEBUG),

    /** 채널 API 호출 실패. 수집 커서를 전진시키지 않고 다음 주기에 재시도한다. */
    CHANNEL_API_FAILED("H008", HttpStatus.BAD_GATEWAY, LogLevel.ERROR),

    /** 어댑터가 아직 구현되지 않은 채널이다. */
    CHANNEL_ADAPTER_NOT_AVAILABLE("H009", HttpStatus.NOT_IMPLEMENTED, LogLevel.DEBUG);

    private final String code;
    private final HttpStatus httpStatus;
    private final LogLevel logLevel;

    @Override
    public String code() {
        return code;
    }

    @Override
    public HttpStatus httpStatus() {
        return httpStatus;
    }

    @Override
    public LogLevel logLevel() {
        return logLevel;
    }
}
