package kr.suhsaechan.palim.common.exception;

/**
 * 팔림 도메인 예외의 최상위 타입.
 *
 * <p>도메인 규칙 위반과 인프라 오류를 구분하기 위해 둔다. 이 타입을 상속한 예외는
 * 애플리케이션이 예상한 상황이므로, 화면에서는 안내 메시지로, 알림에서는 경고로 처리한다.
 */
public abstract class PalimException extends RuntimeException {

    protected PalimException(String message) {
        super(message);
    }

    protected PalimException(String message, Throwable cause) {
        super(message, cause);
    }
}
