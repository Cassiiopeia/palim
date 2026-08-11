package kr.suhsaechan.palim.common.config;

/**
 * 설정값의 타입.
 *
 * <p>값 자체는 전부 {@code jsonb} 한 컬럼에 담긴다. 타입별로 컬럼을 나누면 새 타입이 생길 때마다
 * 스키마가 늘고, 화면은 어느 컬럼을 읽을지 매번 분기해야 한다. 대신 이 힌트로 <b>화면이 어떤
 * 입력 위젯을 그릴지</b>와 <b>서비스가 어떻게 역직렬화할지</b>를 정한다.
 */
public enum ConfigValueType {

    /** 텍스트 입력. */
    STRING,

    /** 정수 입력(스피너). min/max 검증 대상. */
    INTEGER,

    /** 소수 입력(슬라이더). min/max 검증 대상 — 배점·임계값이 여기 속한다. */
    DECIMAL,

    /** 체크박스. */
    BOOLEAN,

    /** 자유 구조(객체·배열). 화면은 JSON 편집기를 띄운다 — 보간 곡선·카테고리 목록 등. */
    JSON
}
