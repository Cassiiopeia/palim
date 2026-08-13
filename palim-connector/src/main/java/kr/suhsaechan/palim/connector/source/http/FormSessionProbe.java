package kr.suhsaechan.palim.connector.source.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 로그인 폼 → 세션 쿠키 → 조회 흐름 검증.
 *
 * <p>웹 화면이 쓰는 것과 같은 경로다. 공개 API 가 유료이거나 없을 때 쓴다.
 *
 * <p><b>이 방식은 상대 화면이 바뀌면 깨진다.</b> 그래서 실패를 조용히 넘기지 않는다 — 수집이
 * 멈춘 줄 모르면 옛 데이터로 대사가 계속 돌고, 그 결과를 믿고 판단하게 된다.
 *
 * <p>쿠키를 손으로 이어붙이는 이유는 {@code RestClient} 가 쿠키 저장소를 갖지 않기 때문이다.
 * 로드밸런서가 붙은 서비스는 세션 쿠키만 넘기면 다른 인스턴스로 가 세션이 끊기므로,
 * <b>응답에 실려 온 쿠키를 전부</b> 다음 요청에 되돌려준다.
 */
@Component
public class FormSessionProbe implements ApiProbe {

    private static final int SAMPLE_LIMIT = 5;
    private static final Pattern HIDDEN_INPUT = Pattern.compile(
            "<input[^>]*name=[\"']?(\\w+)[\"']?[^>]*value=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public FormSessionProbe() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public ApiAuthPreset.AuthFlow flow() {
        return ApiAuthPreset.AuthFlow.FORM_SESSION;
    }

    @Override
    public ProbeReport probe(ProbeRequest request) {
        List<ProbeStep> steps = new ArrayList<>();
        String loginUrl = request.require("loginUrl");
        String fetchUrl = request.require("fetchUrl");
        String userId = request.require("userId");
        String password = request.requireSecret();
        String tokenField = request.params().getOrDefault("tokenField", "token");

        Map<String, String> cookies = new LinkedHashMap<>();
        String token;

        long started = System.nanoTime();
        try {
            var response = restClient.get().uri(loginUrl).retrieve().toEntity(String.class);
            collectCookies(response.getHeaders(), cookies);
            token = findHidden(response.getBody(), tokenField);
            steps.add(ProbeStep.ok("로그인 화면 열기",
                    token.isEmpty() ? "토큰 없음(불필요할 수 있음)" : "토큰 확보", elapsed(started)));
        } catch (Exception e) {
            steps.add(ProbeStep.fail("로그인 화면 열기", reason(e), elapsed(started)));
            steps.add(ProbeStep.skipped("로그인"));
            steps.add(ProbeStep.skipped("데이터 조회"));
            return ProbeReport.of(List.copyOf(steps));
        }

        started = System.nanoTime();
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            request.params().forEach((key, value) -> {
                if (key.endsWith("Field") || key.equals("loginUrl") || key.equals("fetchUrl")
                        || key.equals("fetchBody") || key.equals("rowsPath")) {
                    return;
                }
                form.add(key, value);
            });
            form.set(request.params().getOrDefault("passwordField", "passwd"), password);
            form.set(request.params().getOrDefault("useridField", "userid"), userId);
            if (!token.isEmpty()) {
                form.set(tokenField, token);
            }

            var response = restClient.post().uri(loginUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header(HttpHeaders.COOKIE, cookieHeader(cookies))
                    .body(form)
                    .retrieve().toEntity(String.class);
            collectCookies(response.getHeaders(), cookies);

            if (!hasSession(cookies)) {
                steps.add(ProbeStep.fail("로그인",
                        "세션 쿠키를 받지 못했습니다. 계정 정보를 확인하세요.", elapsed(started)));
                steps.add(ProbeStep.skipped("데이터 조회"));
                return ProbeReport.of(List.copyOf(steps));
            }
            steps.add(ProbeStep.ok("로그인", "세션 쿠키 " + cookies.size() + "개 확보",
                    elapsed(started)));
        } catch (Exception e) {
            steps.add(ProbeStep.fail("로그인", reason(e), elapsed(started)));
            steps.add(ProbeStep.skipped("데이터 조회"));
            return ProbeReport.of(List.copyOf(steps));
        }

        started = System.nanoTime();
        try {
            String body = request.params().getOrDefault("fetchBody", "");
            var response = restClient.post().uri(fetchUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header(HttpHeaders.COOKIE, cookieHeader(cookies))
                    .body(body)
                    .retrieve().toEntity(String.class);

            List<Map<String, String>> rows = parseRows(response.getBody(),
                    request.params().getOrDefault("rowsPath", "rows"));
            if (rows.isEmpty()) {
                steps.add(ProbeStep.fail("데이터 조회",
                        "응답에서 행을 찾지 못했습니다. 응답 경로 설정을 확인하세요.", elapsed(started)));
                return ProbeReport.of(List.copyOf(steps));
            }
            steps.add(ProbeStep.ok("데이터 조회", rows.size() + "행 확인", elapsed(started)));
            return new ProbeReport(steps, List.copyOf(rows.getFirst().keySet()),
                    rows.stream().limit(SAMPLE_LIMIT).toList(), rows.size());
        } catch (Exception e) {
            steps.add(ProbeStep.fail("데이터 조회", reason(e), elapsed(started)));
            return ProbeReport.of(List.copyOf(steps));
        }
    }

    /** 로그인 성공 판정. 세션 쿠키가 생겼는지로 본다 — 실패해도 200 을 돌려주는 화면이 많다. */
    private boolean hasSession(Map<String, String> cookies) {
        return cookies.keySet().stream()
                .anyMatch(name -> name.toUpperCase().contains("SESS"));
    }

    private void collectCookies(HttpHeaders headers, Map<String, String> cookies) {
        List<String> values = headers.get(HttpHeaders.SET_COOKIE);
        if (values == null) {
            return;
        }
        for (String value : values) {
            String pair = value.split(";", 2)[0];
            int index = pair.indexOf('=');
            if (index > 0) {
                cookies.put(pair.substring(0, index).trim(), pair.substring(index + 1).trim());
            }
        }
    }

    private String cookieHeader(Map<String, String> cookies) {
        return cookies.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    private String findHidden(String html, String name) {
        if (html == null) {
            return "";
        }
        Matcher matcher = HIDDEN_INPUT.matcher(html);
        while (matcher.find()) {
            if (name.equalsIgnoreCase(matcher.group(1))) {
                return matcher.group(2);
            }
        }
        return "";
    }

    /** 응답이 JSON 이라는 전제. 지정한 경로의 배열에서 행을 뽑는다. */
    private List<Map<String, String>> parseRows(String body, String rowsPath) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (RuntimeException e) {
            return List.of();
        }
        JsonNode node = root;
        for (String part : rowsPath.split("\\.")) {
            node = node.path(part);
        }
        if (!node.isArray()) {
            return List.of();
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode item : node) {
            // 그리드 응답은 값이 한 겹 더 들어가 있는 경우가 많다 ({"cell":{...}}).
            JsonNode target = item.has("cell") ? item.get("cell") : item;
            if (!target.isObject()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            target.properties().forEach(entry -> row.put(entry.getKey(), stripTags(entry.getValue())));
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }
        return rows;
    }

    /** 화면용 응답은 값에 HTML 태그가 섞여 온다. 그대로 적재하면 매핑이 쓸모없어진다. */
    private static String stripTags(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        String raw = node.isValueNode() ? node.asString() : node.toString();
        return raw.replaceAll("<[^>]*>", "").trim();
    }

    private static String reason(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
