package kr.suhsaechan.palim.connector.source.http;

import org.springframework.web.client.RestClientResponseException;

/**
 * 원격 호출 실패에서 <b>상대 서버가 실제로 준 것</b>을 꺼낸다.
 *
 * <p>연동이 막혔을 때 사람이 알고 싶은 것은 "무엇이 잘못됐는지"인데, 그 답은 거의 항상 응답
 * 본문에 있다. 예외 메시지만 남기면 {@code 400 Bad Request} 같은 껍데기만 보이고,
 * {@code {"Error":{"Message":"invalid cert key"}}} 는 사라진다.
 *
 * <p>그 차이가 크다. 앞쪽만 보면 회사코드가 틀렸는지 키가 만료됐는지 권한이 없는지 알 수 없어
 * 추측으로 시행착오를 반복하게 되고, 테스트용 인증키를 쓰는 상황에서는 그 시행착오가 곧
 * 키 소진이다.
 */
public final class HttpExchange {

    private HttpExchange() {
    }

    /** 상대가 준 상태 코드. 네트워크 단계에서 실패해 응답 자체가 없으면 0. */
    public static int statusOf(Exception e) {
        return e instanceof RestClientResponseException error
                ? error.getStatusCode().value()
                : 0;
    }

    /** 상대가 준 응답 본문. 없으면 예외 종류라도 남긴다 — 빈 화면보다는 낫다. */
    public static String bodyOf(Exception e) {
        if (e instanceof RestClientResponseException error) {
            String body = error.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                return body;
            }
            return "(응답 본문이 비어 있습니다) " + error.getStatusText();
        }
        // 연결 거부·타임아웃·DNS 실패처럼 응답이 아예 없는 경우다.
        StringBuilder chain = new StringBuilder(e.getClass().getSimpleName());
        if (e.getMessage() != null) {
            chain.append(": ").append(e.getMessage());
        }
        Throwable cause = e.getCause();
        int depth = 0;
        while (cause != null && depth < 3) {
            chain.append("\n  원인: ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) {
                chain.append(": ").append(cause.getMessage());
            }
            cause = cause.getCause();
            depth++;
        }
        return chain.toString();
    }

    /** 단계 요약 문구. 자세한 것은 응답 본문에 있으므로 여기서는 짧게 요약한다. */
    public static String summarize(Exception e) {
        int status = statusOf(e);
        if (status == 401 || status == 403) {
            return "인증이 거부됐습니다 (" + status + "). 아래 응답을 확인하세요.";
        }
        if (status >= 400 && status < 500) {
            return "요청이 거부됐습니다 (" + status + "). 아래 응답을 확인하세요.";
        }
        if (status >= 500) {
            return "상대 서버 오류입니다 (" + status + "). 잠시 후 다시 시도하세요.";
        }
        return "서버에 닿지 못했습니다. 주소와 네트워크를 확인하세요.";
    }
}
