package kr.suhsaechan.palim.connector.source.http;

/**
 * 인증 흐름 한 단계의 결과.
 *
 * <p>단계를 나눠 기록하는 이유는 <b>어디서 막혔는지가 곧 원인</b>이기 때문이다. "연결 실패"
 * 한 줄만 남으면 회사코드가 틀린 것인지, 인증키가 만료된 것인지, 조회 권한이 없는 것인지
 * 구분할 수 없어 처음부터 다시 짚어야 한다.
 *
 * @param name      단계 이름 (사람이 읽는다)
 * @param success   성공 여부
 * @param message   성공이면 확인된 값, 실패면 요약된 원인
 * @param elapsedMs 소요 시간. 어느 단계가 느린지 보이면 타임아웃을 어디에 걸지 정할 수 있다
 * @param httpStatus 상대 서버가 준 상태 코드. 모르면 0
 * @param rawResponse 상대 서버 <b>응답 본문 그대로</b>. 아래 설명 참고
 */
public record ProbeStep(String name, boolean success, String message, long elapsedMs,
                        int httpStatus, String rawResponse) {

    /**
     * 응답 본문을 통째로 보관하는 이유.
     *
     * <p>API 연동이 막혔을 때 <b>상대 서버가 뭐라고 했는지가 유일한 단서</b>다. 예외 메시지만
     * 남기면 "500 Internal Server Error" 같은 것만 보이고, 정작 본문에 들어 있는
     * {@code {"Error":{"Message":"invalid cert key"}}} 는 사라진다. 그러면 키가 틀린 것인지
     * 권한이 없는 것인지 알 수 없어 추측으로 시행착오를 반복하게 된다.
     *
     * <p>길이를 제한하는 것은 화면과 로그를 위해서다. 원인은 대개 앞부분에 있다.
     */
    private static final int RAW_LIMIT = 4000;

    public ProbeStep {
        if (rawResponse != null && rawResponse.length() > RAW_LIMIT) {
            rawResponse = rawResponse.substring(0, RAW_LIMIT) + "\n… (이후 생략)";
        }
    }

    public static ProbeStep ok(String name, String message, long elapsedMs) {
        return new ProbeStep(name, true, message, elapsedMs, 200, null);
    }

    public static ProbeStep ok(String name, String message, long elapsedMs, String rawResponse) {
        return new ProbeStep(name, true, message, elapsedMs, 200, rawResponse);
    }

    public static ProbeStep fail(String name, String message, long elapsedMs) {
        return new ProbeStep(name, false, message, elapsedMs, 0, null);
    }

    public static ProbeStep fail(String name, String message, long elapsedMs, int httpStatus,
                                 String rawResponse) {
        return new ProbeStep(name, false, message, elapsedMs, httpStatus, rawResponse);
    }

    /** 앞 단계가 실패해 아예 시도하지 못한 단계. 실패와 구분해야 원인이 흐려지지 않는다. */
    public static ProbeStep skipped(String name) {
        return new ProbeStep(name, false, "앞 단계가 실패해 실행하지 않았습니다", 0, 0, null);
    }

    /** 화면이 "응답 보기"를 열지 결정한다. */
    public boolean hasRawResponse() {
        return rawResponse != null && !rawResponse.isBlank();
    }
}
