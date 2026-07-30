package kr.suhsaechan.palim.common.exception;

/**
 * 이미 존재하는 값을 중복 등록하려 할 때 발생한다.
 *
 * <p>주의 — 채널 주문의 중복 수집에는 이 예외를 쓰지 않는다. 그쪽은 데이터베이스 유니크
 * 제약 위반을 잡아 "이미 처리된 주문"으로 조용히 건너뛰는 것이 정상 흐름이다(설계서 5.1).
 * 이 예외는 사용자가 화면에서 중복 입력을 시도한 경우에 쓴다.
 */
public class DuplicateException extends PalimException {

    public DuplicateException(String message) {
        super(message);
    }

    public static DuplicateException of(String targetName, Object identifier) {
        return new DuplicateException("%s이(가) 이미 존재합니다: %s".formatted(targetName, identifier));
    }
}
