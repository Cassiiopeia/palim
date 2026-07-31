package kr.suhsaechan.palim.web.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 경로를 화면 이름으로 바꾼다.
 *
 * <p>감사 로그의 "내용" 열에 URI 대신 화면 이름을 남긴다. URI 만 남기면 <b>나중에 경로를 바꿨을
 * 때 과거 기록을 읽을 수 없다.</b> URI 자체는 {@code request_uri} 컬럼에 따로 보관한다.
 *
 * <p>등록되지 않은 경로는 조회 감사에서 제외한다. 화면이 아닌 것(정적 자원 · SSE · 헬스체크)까지
 * 기록하면 감사 로그가 잡음으로 가득 차 <b>정작 봐야 할 기록이 묻힌다.</b>
 */
final class ScreenNames {

    /**
     * 경로 접두사 → 화면 이름.
     *
     * <p>긴 접두사를 먼저 넣는다. {@code /skus/{id}} 가 {@code /skus} 보다 먼저 매칭돼야 한다.
     */
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put("/settings/channels", "채널 설정");
        NAMES.put("/settings/notification", "알림 설정");
        NAMES.put("/settings/account", "계정 설정");
        NAMES.put("/monitor/collect", "수집 모니터");
        NAMES.put("/mappings", "상품 매핑");
        NAMES.put("/audit", "감사 로그");
        NAMES.put("/skus", "재고 관리");
        NAMES.put("/", "대시보드");
    }

    private ScreenNames() {
    }

    /** 화면 이름. 감사 대상이 아니면 {@code null}. */
    static String of(String requestUri) {
        if (requestUri == null) {
            return null;
        }
        if ("/".equals(requestUri)) {
            return NAMES.get("/");
        }
        return NAMES.entrySet().stream()
                .filter(entry -> !"/".equals(entry.getKey()))
                .filter(entry -> requestUri.equals(entry.getKey())
                        || requestUri.startsWith(entry.getKey() + "/"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
