package kr.suhsaechan.palim.web.audit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청의 클라이언트 IP 를 뽑는다.
 *
 * <h2>{@code X-Forwarded-For} 를 직접 파싱하지 않는다</h2>
 *
 * <p>사내 CM · DLPCenter 는 {@code request.getRemoteAddr()} 만 쓴다. 리버스 프록시 뒤에 놓이면
 * <b>모든 감사 기록의 IP 가 프록시 주소로 찍혀</b> 감사 로그의 IP 열이 무의미해진다.
 *
 * <p>반대로 헤더를 무조건 신뢰하면 클라이언트가 {@code X-Forwarded-For} 를 위조해 IP 를 마음대로
 * 적을 수 있다. <b>감사 로그에 위조된 IP 가 남는 것은 IP 가 없는 것보다 나쁘다.</b>
 *
 * <p>그래서 헤더 해석은 Spring 의 {@code ForwardedHeaderFilter} 에 맡기고
 * ({@code server.forward-headers-strategy=framework}), 이 클래스는 결과를 정규화만 한다.
 * 필터가 꺼져 있으면 {@code getRemoteAddr()} 이 그대로 반환되므로 <b>기본값은 위조 불가</b>다.
 * Palim 은 Cloudflare Tunnel 뒤에서 동작하므로 운영 프로파일에서 켠다(06-OPERATIONS).
 */
public final class ClientIpResolver {

    /** IP 를 알 수 없을 때 쓰는 값. 컬럼을 NULL 로 두면 화면에서 빈 칸이 되어 오해를 준다. */
    public static final String UNKNOWN = "unknown";

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        return normalize(request.getRemoteAddr());
    }

    /**
     * 표시용으로 정규화한다.
     *
     * <p>로컬 접속은 IPv6 형태({@code 0:0:0:0:0:0:0:1})로 들어오는데, 그대로 남기면 목록에서
     * 열 너비를 잡아먹고 읽기 어렵다. IPv4 표기로 바꾼다.
     */
    static String normalize(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return UNKNOWN;
        }
        String value = remoteAddr.trim();
        if ("::1".equals(value) || "0:0:0:0:0:0:0:1".equals(value)) {
            return "127.0.0.1";
        }
        // IPv4-mapped IPv6 (::ffff:10.0.0.1)
        int mapped = value.lastIndexOf(':');
        if (value.startsWith("::ffff:") && mapped >= 0 && value.indexOf('.') > mapped) {
            return value.substring(mapped + 1);
        }
        return value;
    }
}
