package kr.suhsaechan.palim.connector.run;

/**
 * 실행 모드.
 *
 * <p>{@code TEST} 는 {@code connector_staging} 에만 쓴다. 운영 테이블에 닿지 않으므로
 * 지우기 전에 도메인 로직이 읽어 오염된 결과를 내는 일이 없다.
 */
public enum RunMode { TEST, LIVE }
