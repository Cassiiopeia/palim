package kr.suhsaechan.palim.connector.source.http;

/**
 * 인증 흐름 한 단계의 결과.
 *
 * <p>단계를 나눠 기록하는 이유는 <b>어디서 막혔는지가 곧 원인</b>이기 때문이다. "연결 실패"
 * 한 줄만 남으면 회사코드가 틀린 것인지, 인증키가 만료된 것인지, 조회 권한이 없는 것인지
 * 구분할 수 없어 처음부터 다시 짚어야 한다.
 *
 * @param name     단계 이름 (사람이 읽는다)
 * @param success  성공 여부
 * @param message  성공이면 확인된 값, 실패면 원인. <b>인증키를 담지 않는다</b>
 * @param elapsedMs 소요 시간. 어느 단계가 느린지 보이면 타임아웃을 어디에 걸지 정할 수 있다
 */
public record ProbeStep(String name, boolean success, String message, long elapsedMs) {

    public static ProbeStep ok(String name, String message, long elapsedMs) {
        return new ProbeStep(name, true, message, elapsedMs);
    }

    public static ProbeStep fail(String name, String message, long elapsedMs) {
        return new ProbeStep(name, false, message, elapsedMs);
    }

    /** 앞 단계가 실패해 아예 시도하지 못한 단계. 실패와 구분해야 원인이 흐려지지 않는다. */
    public static ProbeStep skipped(String name) {
        return new ProbeStep(name, false, "앞 단계가 실패해 실행하지 않았습니다", 0);
    }
}
