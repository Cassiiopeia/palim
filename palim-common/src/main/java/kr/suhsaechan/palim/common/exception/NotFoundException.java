package kr.suhsaechan.palim.common.exception;

/**
 * 식별자로 조회한 대상이 없을 때 발생한다.
 *
 * <p>도메인 모듈은 서로를 의존하지 않고 UUID 값으로만 참조하므로, 참조 대상이 실제로
 * 존재하는지는 조회 시점에야 알 수 있다. 이 예외가 그 지점을 드러낸다.
 */
public class NotFoundException extends PalimException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException of(String targetName, Object identifier) {
        return new NotFoundException("%s을(를) 찾을 수 없습니다: %s".formatted(targetName, identifier));
    }
}
