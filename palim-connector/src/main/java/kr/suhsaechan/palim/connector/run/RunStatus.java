package kr.suhsaechan.palim.connector.run;

/**
 * 실행 결과 상태.
 *
 * <p>{@code PARTIAL} 을 별도로 두는 이유는 부분 실패가 정상 상황이기 때문이다. 성공/실패
 * 둘로만 나누면 실패 행이 묻히거나 성공분까지 버린 것으로 오해된다.
 */
public enum RunStatus { RUNNING, SUCCEEDED, PARTIAL, FAILED, ROLLED_BACK }
