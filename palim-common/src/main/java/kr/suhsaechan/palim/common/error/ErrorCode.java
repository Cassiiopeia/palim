package kr.suhsaechan.palim.common.error;

import org.springframework.boot.logging.LogLevel;
import org.springframework.http.HttpStatus;

/**
 * 실패 유형 식별자.
 *
 * <h2>왜 enum 이 아니라 인터페이스인가</h2>
 *
 * <p>공통 enum 하나에 모든 에러코드를 모으면 두 가지가 무너진다.
 *
 * <ul>
 *   <li>도메인이 추가될 때마다 <b>공통 파일을 수정</b>해야 한다
 *   <li>{@code palim-common} 이 SKU·주문·채널 사정을 전부 알게 되어 <b>모듈 격리가 깨진다</b>
 * </ul>
 *
 * <p>인터페이스로 두면 각 도메인이 자기 에러코드를 소유하고, 새 도메인 추가는 파일 생성만으로
 * 끝난다. 기존 파일을 건드리지 않는다.
 *
 * <h2>구현 규칙</h2>
 *
 * <p>enum 으로 구현한다. {@link #name()} 은 enum 이 자동 제공하므로 별도 구현이 필요 없고,
 * 이 값이 곧 클라이언트가 분기에 쓰는 식별자이며 메시지 키의 근거가 된다.
 *
 * <p>{@link #code()} 는 도메인 접두사 + 세 자리 숫자다 — 공통 {@code C}, SKU {@code S},
 * 주문 {@code O}, 매핑 {@code M}, 채널 {@code H}, 알림 {@code N}, 인증 {@code A}.
 */
public interface ErrorCode {

    /** enum 이 자동 제공한다. 클라이언트가 분기에 쓰는 식별자다. */
    String name();

    /** 도메인 접두사 + 세 자리 숫자. 예 {@code S001} */
    String code();

    /** 이 실패가 HTTP 로 표현될 때의 상태. */
    HttpStatus httpStatus();

    /** 메시지 프로퍼티 키. 기본은 {@code error.} + enum 이름이다. */
    default String messageKey() {
        return "error." + name();
    }

    /**
     * 이 실패를 어느 수준으로 로깅할지.
     *
     * <p>전역 핸들러가 예외 종류마다 if 분기로 레벨을 정하면 새 코드가 추가될 때마다 핸들러를
     * 고쳐야 한다. ErrorCode 가 스스로 정하게 하면 핸들러는 그대로 둔다.
     *
     * <p>대표 사례가 중복 수집이다. 수집 커서는 구간을 겹쳐 조회하므로 같은 주문이 반복
     * 수집되는 것이 정상이고, 이를 오류로 로깅하면 로그가 무의미해진다. 해당 코드만
     * {@link LogLevel#DEBUG} 로 두면 핸들러 수정 없이 해결된다.
     */
    default LogLevel logLevel() {
        return LogLevel.WARN;
    }
}
