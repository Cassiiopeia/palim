package kr.suhsaechan.palim.channel;

import kr.suhsaechan.palim.common.exception.PalimException;

/**
 * 인증정보 암복호화 실패.
 *
 * <p>복호화 실패는 마스터키가 교체되었거나 암호문이 손상된 상황이다. 해당 채널의 수집이
 * 즉시 중단되므로 텔레그램 경고 대상이다.
 */
public class CredentialCipherException extends PalimException {

    public CredentialCipherException(String message, Throwable cause) {
        super(message, cause);
    }
}
