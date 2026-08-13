package kr.suhsaechan.palim.connector.source.http;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 지역 조회 → 로그인 → 조회 3단계 인증 흐름 검증.
 *
 * <p>절차가 셋으로 나뉜 시스템은 각 단계가 서로 다른 이유로 실패한다. 회사코드가 틀리면 1단계,
 * 인증키가 만료됐거나 허용 ID 가 다르면 2단계, 조회 권한이 없으면 3단계에서 막힌다. 그래서
 * <b>단계를 합치지 않고 각각 기록</b>한다.
 *
 * <p><b>테스트용 인증키는 업무 API 를 한 번 성공시키면 소진된다.</b> 그래서 이 검증은 한 번에
 * 끝나야 하고, 성공했을 때 응답 필드와 샘플까지 함께 돌려준다 — 매핑을 짜려고 다시 호출하면
 * 그때는 키가 죽어 있다.
 */
@Component
public class ZoneSessionProbe implements ApiProbe {

    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int SAMPLE_LIMIT = 5;

    /**
     * 접속 주소 기본값.
     *
     * <p>테스트키와 정식키는 접속 주소가 다르다. 섞어 쓰면 인증이 통과하지 않는다.
     *
     * <p>여기 값은 <b>기본값일 뿐</b>이고 화면에서 바꿀 수 있다. 특정 벤더 주소를 코드에 고정하면
     * 지역이 다른 같은 제품이나 다른 시스템에 붙일 때 코드를 고쳐야 한다.
     */
    private static final String DEFAULT_SANDBOX_PREFIX = "sboapi";
    private static final String DEFAULT_LIVE_PREFIX = "oapi";
    private static final String DEFAULT_DOMAIN = "ecount.com";

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ZoneSessionProbe() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 응답이 없는 원격을 무한정 기다리면 화면이 멈춘 것처럼 보인다.
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public ApiAuthPreset.AuthFlow flow() {
        return ApiAuthPreset.AuthFlow.ZONE_SESSION;
    }

    @Override
    public ProbeReport probe(ProbeRequest request) {
        List<ProbeStep> steps = new ArrayList<>();
        String companyCode = request.require("companyCode");
        String userId = request.require("userId");
        String apiKey = request.requireSecret();

        String domain = request.params().getOrDefault("apiDomain", DEFAULT_DOMAIN);
        String sandboxPrefix = request.params()
                .getOrDefault("sandboxPrefix", DEFAULT_SANDBOX_PREFIX);
        String livePrefix = request.params().getOrDefault("livePrefix", DEFAULT_LIVE_PREFIX);

        // 지역 조회도 환경을 따른다. 테스트 환경에는 "테스트용으로 따로 만든 회사"만 있고
        // 실제 운영 회사는 없다. 여기를 고정하면 운영 회사를 넣어도 늘 빈 지역이 돌아온다.
        String prefix = request.sandbox() ? sandboxPrefix : livePrefix;

        String zone = null;
        long started = System.nanoTime();
        try {
            // 주소를 통째로 덮어쓸 수 있게 둔다. 기본 조립이 맞지 않는 환경(지역이 다른 같은
            // 제품, 사설망 게이트웨이)에서 코드를 고치지 않고 화면에서 바꿀 수 있어야 한다.
            String zoneUrl = request.params().getOrDefault("zoneUrl",
                    "https://%s.%s/OAPI/V2/Zone".formatted(prefix, domain));
            JsonNode response = post(zoneUrl, Map.of("COM_CODE", companyCode));
            zone = text(response.path("Data").path("ZONE"));
            if (zone.isEmpty()) {
                // 상대가 알려주는 신호를 그대로 읽어 원인을 짚어 준다. 이 구분이 없으면
                // 사용자는 회사코드가 틀린 줄 알고 맞는 값을 계속 다시 넣는다.
                boolean emptyZone = response.path("Data").path("EMPTY_ZONE").asBoolean(false);
                String hint = emptyZone
                        ? (request.sandbox()
                            ? "이 회사코드가 테스트 환경에 없습니다. 실제 운영 회사라면 위의 "
                              + "'테스트 환경으로 접속'을 끄고 다시 시도하세요."
                            : "이 회사코드에 해당하는 지역이 없습니다. 회사코드를 확인하세요.")
                        : "응답에 지역 값이 없습니다. 회사코드를 확인하세요.";
                steps.add(ProbeStep.fail("지역 조회", hint, elapsed(started), 200,
                        response.toString()));
                steps.add(ProbeStep.skipped("로그인"));
                steps.add(ProbeStep.skipped("재고 조회"));
                return finish(steps);
            }
            steps.add(ProbeStep.ok("지역 조회", "지역 = " + zone, elapsed(started)));
        } catch (Exception e) {
            steps.add(ProbeStep.fail("지역 조회", HttpExchange.summarize(e), elapsed(started),
                    HttpExchange.statusOf(e), HttpExchange.bodyOf(e)));
            steps.add(ProbeStep.skipped("로그인"));
            steps.add(ProbeStep.skipped("재고 조회"));
            return finish(steps);
        }

        String base = request.params().getOrDefault("apiBase",
                "https://%s%s.%s/OAPI/V2".formatted(prefix, zone, domain));

        String sessionId;
        started = System.nanoTime();
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("COM_CODE", companyCode);
            body.put("USER_ID", userId);
            body.put("API_CERT_KEY", apiKey);
            body.put("LAN_TYPE", "ko-KR");
            body.put("ZONE", zone);
            JsonNode response = post(base + "/OAPILogin", body);
            sessionId = text(response.path("Data").path("Datas").path("SESSION_ID"));
            if (sessionId.isEmpty()) {
                // 인증키가 특정 사용자 ID 에 묶여 있어, 발급 시 지정한 ID 와 다르면 여기서 막힌다.
                steps.add(ProbeStep.fail("로그인",
                        "세션을 받지 못했습니다. 인증키가 이 사용자 ID 로 발급된 것인지 확인하세요.",
                        elapsed(started), 200, response.toString()));
                steps.add(ProbeStep.skipped("재고 조회"));
                return finish(steps);
            }
            steps.add(ProbeStep.ok("로그인", "세션 발급됨", elapsed(started)));
        } catch (Exception e) {
            steps.add(ProbeStep.fail("로그인", HttpExchange.summarize(e), elapsed(started),
                    HttpExchange.statusOf(e), HttpExchange.bodyOf(e)));
            steps.add(ProbeStep.skipped("재고 조회"));
            return finish(steps);
        }

        started = System.nanoTime();
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("PROD_CD", "");
            body.put("WH_CD", "");
            body.put("BASE_DATE", request.baseDate().format(COMPACT_DATE));
            JsonNode response = post(
                    base + "/InventoryBalance/GetListInventoryBalanceStatusByLocation?SESSION_ID="
                            + sessionId, body);

            List<Map<String, String>> samples = extractRows(response);
            if (samples.isEmpty()) {
                // 연결은 됐는데 데이터가 없는 것과 연결이 안 된 것은 다르다. 구분해서 알린다.
                steps.add(ProbeStep.ok("재고 조회",
                        "호출은 성공했지만 해당 기준일에 재고가 없습니다. 기준일을 바꿔 보세요.",
                        elapsed(started)));
                return finish(steps);
            }
            steps.add(ProbeStep.ok("재고 조회", samples.size() + "행 이상 확인", elapsed(started)));
            return new ProbeReport(steps, List.copyOf(samples.getFirst().keySet()),
                    samples.stream().limit(SAMPLE_LIMIT).toList(), -1);
        } catch (Exception e) {
            steps.add(ProbeStep.fail("재고 조회", HttpExchange.summarize(e), elapsed(started),
                    HttpExchange.statusOf(e), HttpExchange.bodyOf(e)));
            return finish(steps);
        }
    }

    /**
     * 본문을 <b>문자열로 직렬화해서</b> 보낸다.
     *
     * <p>객체를 그대로 넘기면 HTTP 클라이언트에 등록된 JSON 변환기가 처리해야 하는데, 그 변환기가
     * 없으면 <b>빈 본문이 조용히 나간다.</b> 상대 서버는 "값이 없다"는 정상 응답(200)을 돌려주므로
     * 예외가 나지 않고, 코드는 성공한 줄 안다. 화면에는 "회사코드를 확인하세요"가 뜨고, 사용자는
     * 맞는 값을 넣고도 원인을 찾을 수 없다 — 실제로 그렇게 한 번 겪었다.
     *
     * <p>문자열로 만들어 보내면 변환기 구성에 기대지 않는다. 응답도 문자열로 받아 직접 파싱한다.
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

    /**
     * 응답에서 행 목록을 찾는다.
     *
     * <p>응답 구조가 문서와 다른 일이 잦아 <b>알려진 경로를 순서대로 시도한 뒤, 없으면 배열을
     * 찾아 나선다.</b> 여기서 실패하면 사람이 응답을 직접 봐야 하는데, 테스트키가 이미 소진된
     * 뒤라 다시 호출할 수 없다.
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

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.isValueNode() ? node.asString() : node.toString();
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static ProbeReport finish(List<ProbeStep> steps) {
        return ProbeReport.of(List.copyOf(steps));
    }
}
