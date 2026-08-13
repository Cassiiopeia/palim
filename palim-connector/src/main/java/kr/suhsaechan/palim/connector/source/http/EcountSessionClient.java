package kr.suhsaechan.palim.connector.source.http;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 지역 조회 → 로그인 → 업무 조회.
 *
 * <p>이 절차를 <b>검증 화면에서 떼어낸 이유</b>가 있다. 연결 확인({@link ZoneSessionProbe})은
 * 단계별 성공·실패를 사람에게 보여주는 것이 목적이라 리포트를 조립하지만, 실제 수집에 필요한
 * 것은 자료뿐이다. 두 목적을 한 클래스에 두면 수집 경로가 리포트 조립까지 끌고 가게 된다.
 *
 * <p>그렇다고 인증을 두 벌 짜면 한쪽만 고쳐져 어긋난다. 그 어긋남은 <b>"연결 확인은 통과하는데
 * 수집은 실패한다"</b> 는 모양으로 나타나 원인을 찾기 어렵다. 그래서 절차는 여기 한 곳에 두고,
 * 검증 화면과 수집 어댑터가 함께 쓴다.
 */
@Component
public class EcountSessionClient {

    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public EcountSessionClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 응답이 없는 원격을 무한정 기다리면 화면이 멈춘 것처럼 보인다.
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 접속 주소 조립에 필요한 것.
     *
     * <p>테스트 환경과 운영 환경의 주소 접두어가 다르다. 섞어 쓰면 인증이 통과하지 않는다.
     * 통째로 덮어쓸 수 있게 둔 것은 기본 조립이 맞지 않는 환경(지역이 다른 같은 제품, 사설망
     * 게이트웨이)에서 코드를 고치지 않고 설정으로 해결하기 위해서다.
     *
     * @param domain            서비스 도메인
     * @param sandboxPrefix     테스트 환경 접두어
     * @param livePrefix        운영 환경 접두어
     * @param sandbox           지금 테스트 환경인가
     * @param zoneUrlOverride   지역 조회 주소를 직접 지정할 때. 비면 조립한다
     * @param apiBaseOverride   업무 API 기준 주소를 직접 지정할 때. 비면 조립한다
     */
    public record EcountEndpoint(String domain, String sandboxPrefix, String livePrefix,
                                 boolean sandbox, String zoneUrlOverride,
                                 String apiBaseOverride) {

        public String zoneUrl() {
            return StringUtils.hasText(zoneUrlOverride) ? zoneUrlOverride
                    : "https://%s.%s/OAPI/V2/Zone".formatted(prefix(), domain);
        }

        public String apiBase(String zone) {
            return StringUtils.hasText(apiBaseOverride) ? apiBaseOverride
                    : "https://%s%s.%s/OAPI/V2".formatted(prefix(), zone, domain);
        }

        private String prefix() {
            return sandbox ? sandboxPrefix : livePrefix;
        }
    }

    /**
     * 회사코드로 지역을 찾는다.
     *
     * <p>지역이 비었는데 다음 단계로 넘어가면 원인이 상대 서버 메시지로 흐려진다. 여기서 막는다.
     */
    public String resolveZone(EcountEndpoint endpoint, String companyCode) {
        JsonNode response = post(endpoint.zoneUrl(), Map.of("COM_CODE", companyCode));
        String zone = text(response.path("Data").path("ZONE"));
        if (zone.isEmpty()) {
            throw failure(response, "지역을 찾지 못했습니다. 회사코드를 확인하세요.");
        }
        return zone;
    }

    /** 인증키로 세션을 받는다. 인증키는 발급 시 지정한 사용자 ID 에 묶인다. */
    public String login(EcountEndpoint endpoint, String zone, String companyCode, String userId,
                        String apiKey) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("COM_CODE", companyCode);
        body.put("USER_ID", userId);
        body.put("API_CERT_KEY", apiKey);
        body.put("LAN_TYPE", "ko-KR");
        body.put("ZONE", zone);

        JsonNode response = post(endpoint.apiBase(zone) + "/OAPILogin", body);
        String sessionId = text(response.path("Data").path("Datas").path("SESSION_ID"));
        if (sessionId.isEmpty()) {
            throw failure(response, "세션을 받지 못했습니다.");
        }
        return sessionId;
    }

    /** 기준일의 위치별 재고. 행이 없으면 빈 목록이다 — 그것은 실패가 아니다. */
    public List<Map<String, String>> fetchInventory(EcountEndpoint endpoint, String zone,
                                                    String sessionId, LocalDate baseDate) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("PROD_CD", "");
        body.put("WH_CD", "");
        body.put("BASE_DATE", baseDate.format(COMPACT_DATE));

        JsonNode response = post(
                endpoint.apiBase(zone)
                        + "/InventoryBalance/GetListInventoryBalanceStatusByLocation?SESSION_ID="
                        + sessionId, body);

        List<Map<String, String>> rows = extractRows(response);
        if (rows.isEmpty()) {
            // 행이 없는 이유는 둘이다 — 정말 없거나, 거부당했거나. 상대가 사유를 보냈다면 후자다.
            String message = text(response.path("Data").path("Message"));
            if (!message.isBlank()) {
                throw failure(response, message);
            }
        }
        return rows;
    }

    /**
     * 응답 원문을 예외에 실어 보낸다.
     *
     * <p>연동이 막혔을 때 <b>상대가 통째로 뭐라고 했는지가 유일한 단서</b>다. 요약만 남기면
     * 그 안에 들어 있던 결정적 정보가 사라진다 — 실제로 "허용되지 않은 IP" 응답에 등록해야 할
     * 주소가 대괄호로 적혀 있었고, 원문이 없었다면 그 값을 알 방법이 없었다.
     *
     * <p>검증 화면은 이 원문을 꺼내 사람에게 접어서 보여준다. 수집 경로는 사유만 쓰고 원문은
     * 무시한다 — 필요한 쪽만 꺼내 쓰면 된다.
     */
    private BusinessException failure(JsonNode response, String fallback) {
        return new BusinessException(ErrorCode.API_PROBE_FAILED, reasonOf(response, fallback))
                .withDetails(Map.of(RAW_RESPONSE, response.toString()));
    }

    /** 예외에 실린 응답 원문을 꺼낼 때 쓰는 키. */
    public static final String RAW_RESPONSE = "rawResponse";

    /**
     * 본문을 <b>문자열로 직렬화해서</b> 보낸다.
     *
     * <p>객체를 그대로 넘기면 HTTP 클라이언트에 등록된 JSON 변환기가 처리해야 하는데, 직접 만든
     * 클라이언트에는 그 변환기가 없어 <b>빈 본문이 조용히 나간다.</b> 상대는 "값이 없다"는 정상
     * 응답(200)을 돌려주므로 예외가 나지 않고, 코드는 성공한 줄 안다. 실제로 그렇게 한 번 겪었고,
     * 요청을 직접 받아 보기 전까지는 아무도 알아채지 못했다.
     */
    private JsonNode post(String url, Map<String, String> body) {
        String payload = mapper.writeValueAsString(body);
        String response = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(String.class);
        return mapper.readTree(response == null ? "{}" : response);
    }

    /** 실패 사유는 상대가 보낸 말을 먼저 쓴다. 우리 짐작이 상대의 답을 가리면 안 된다. */
    private static String reasonOf(JsonNode response, String fallback) {
        String message = text(response.path("Data").path("Message"));
        return message.isBlank() ? fallback : message;
    }

    /**
     * 응답에서 행 목록을 찾는다.
     *
     * <p>응답 구조가 문서와 다른 일이 잦아 <b>알려진 경로를 순서대로 시도한 뒤, 없으면 배열을
     * 찾아 나선다.</b> 여기서 실패하면 사람이 응답을 직접 봐야 하는데, 테스트키를 쓰는 상황에서는
     * 이미 소진된 뒤라 다시 호출할 수 없다.
     */
    private List<Map<String, String>> extractRows(JsonNode response) {
        for (String path : List.of("Data.Result", "Data.Datas", "Data")) {
            JsonNode node = response;
            for (String part : path.split("\\.")) {
                node = node.path(part);
            }
            if (node.isArray() && !node.isEmpty()) {
                return toRows(node);
            }
        }
        JsonNode found = firstArray(response, 0);
        return found == null ? List.of() : toRows(found);
    }

    private JsonNode firstArray(JsonNode node, int depth) {
        if (depth > 5 || node == null) {
            return null;
        }
        if (node.isArray() && !node.isEmpty() && node.get(0).isObject()) {
            return node;
        }
        for (JsonNode child : node) {
            JsonNode found = firstArray(child, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private List<Map<String, String>> toRows(JsonNode array) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isObject()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            item.properties().forEach(entry -> row.put(entry.getKey(), text(entry.getValue())));
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }
        return rows;
    }

    static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.isValueNode() ? node.asString() : node.toString();
    }
}
