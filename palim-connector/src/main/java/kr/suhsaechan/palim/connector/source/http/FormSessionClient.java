package kr.suhsaechan.palim.connector.source.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 로그인 화면을 사람처럼 통과해 자료를 받아온다.
 *
 * <p>공개 API 가 없거나 유료인 시스템이 있다. 그때는 <b>화면이 쓰는 경로를 그대로</b> 쓴다 —
 * 로그인 폼을 전송해 세션 쿠키를 받고, 화면이 호출하는 조회 요청을 같은 형식으로 보낸다.
 *
 * <p><b>상대 화면이 바뀌면 깨진다.</b> 그래서 수집 실패를 반드시 드러내야 한다. 조용히 멈추면
 * 옛 자료로 대조가 계속 돌고, 사람은 그 결과를 믿고 판단한다.
 *
 * <p>{@link FormSessionProbe} 와 이 클래스의 관계는 {@link EcountSessionClient} 와
 * {@link ZoneSessionProbe} 의 관계와 같다 — 절차는 여기 한 곳에 두고, 검증 화면과 수집
 * 어댑터가 함께 쓴다.
 */
@Component
public class FormSessionClient {

    /** 로그인 폼에 숨어 있는 토큰. 없는 화면도 있어 못 찾아도 실패로 보지 않는다. */
    private static final Pattern HIDDEN_INPUT = Pattern.compile(
            "<input[^>]*name=[\"']%s[\"'][^>]*value=[\"']([^\"']*)[\"']");

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public FormSessionClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** 로그인해서 얻은 세션. 조회할 때 그대로 실어 보낸다. */
    public record Session(Map<String, String> cookies) {
    }

    /**
     * 로그인 화면을 열어 토큰과 첫 쿠키를 받는다.
     *
     * <p>토큰이 없는 화면도 있다. 없다고 실패로 보면 그런 시스템은 아예 붙일 수 없다.
     */
    public String openLoginPage(String loginUrl, String tokenField, Map<String, String> cookies) {
        ResponseEntity<String> response =
                restClient.get().uri(loginUrl).retrieve().toEntity(String.class);
        collectCookies(response.getHeaders(), cookies);
        return findHidden(response.getBody(), tokenField);
    }

    /**
     * 폼을 보내 세션을 받는다.
     *
     * <p>성공 판정을 <b>세션 쿠키가 생겼는지</b>로 한다. 로그인에 실패해도 200 을 돌려주는
     * 화면이 많아 상태 코드로는 가릴 수 없다.
     */
    public Session login(Map<String, String> config, String userId, String password,
                         Map<String, String> cookies, String token) {
        String loginUrl = required(config, "loginUrl");
        String tokenField = config.getOrDefault("tokenField", "token");

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        config.forEach((key, value) -> {
            // 설정 항목은 폼에 넣지 않는다. 상대 서버가 모르는 값이 섞이면 거부하는 경우가 있다.
            if (key.endsWith("Field") || key.equals("loginUrl") || key.equals("fetchUrl")
                    || key.equals("fetchBody") || key.equals("rowsPath")
                    || key.equals("preset") || key.equals("sandbox")) {
                return;
            }
            form.add(key, value);
        });
        form.set(config.getOrDefault("passwordField", "passwd"), password);
        form.set(config.getOrDefault("useridField", "userid"), userId);
        if (!token.isEmpty()) {
            form.set(tokenField, token);
        }

        ResponseEntity<String> response = restClient.post().uri(loginUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.COOKIE, cookieHeader(cookies))
                .body(form)
                .retrieve().toEntity(String.class);
        collectCookies(response.getHeaders(), cookies);

        if (!hasSession(cookies)) {
            throw new BusinessException(ErrorCode.API_PROBE_FAILED,
                    "세션을 받지 못했습니다. 계정 정보를 확인하세요.")
                    .withDetails(Map.of(EcountSessionClient.RAW_RESPONSE,
                            String.valueOf(response.getBody())));
        }
        return new Session(cookies);
    }

    /** 화면이 호출하는 조회 요청을 같은 형식으로 보낸다. */
    public List<Map<String, String>> fetch(Map<String, String> config, Session session) {
        String fetchUrl = required(config, "fetchUrl");
        String body = config.getOrDefault("fetchBody", "");

        ResponseEntity<String> response = restClient.post().uri(fetchUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.COOKIE, cookieHeader(session.cookies()))
                .body(body)
                .retrieve().toEntity(String.class);

        List<Map<String, String>> rows =
                parseRows(response.getBody(), config.getOrDefault("rowsPath", "rows"));
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.API_PROBE_FAILED,
                    "응답에서 행을 찾지 못했습니다. 상대 화면이 바뀌었을 수 있습니다.")
                    .withDetails(Map.of(EcountSessionClient.RAW_RESPONSE,
                            String.valueOf(response.getBody())));
        }
        return rows;
    }

    /** 로그인 성공 판정. 실패해도 200 을 돌려주는 화면이 많아 쿠키로 본다. */
    public boolean hasSession(Map<String, String> cookies) {
        return cookies.keySet().stream()
                .anyMatch(name -> name.toUpperCase(java.util.Locale.ROOT).contains("SESS"));
    }

    public void collectCookies(HttpHeaders headers, Map<String, String> cookies) {
        List<String> values = headers.get(HttpHeaders.SET_COOKIE);
        if (values == null) {
            return;
        }
        for (String raw : values) {
            String pair = raw.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq > 0) {
                cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
    }

    public String cookieHeader(Map<String, String> cookies) {
        return cookies.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    public String findHidden(String html, String name) {
        if (html == null || name == null || name.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile(HIDDEN_INPUT.pattern().formatted(Pattern.quote(name)))
                .matcher(html);
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * 응답에서 행 목록을 찾는다.
     *
     * <p>화면이 쓰는 경로라 응답 모양이 제품마다 다르다. 지정한 경로를 먼저 보고, 없으면 배열을
     * 찾아 나선다.
     */
    public List<Map<String, String>> parseRows(String body, String rowsPath) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode node = root.path(rowsPath);
            if (!node.isArray()) {
                node = firstArray(root, 0);
            }
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<Map<String, String>> rows = new ArrayList<>();
            for (JsonNode item : node) {
                Map<String, String> row = new LinkedHashMap<>();
                if (item.isObject()) {
                    item.properties().forEach(entry ->
                            row.put(entry.getKey(), text(entry.getValue())));
                }
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
            return rows;
        } catch (RuntimeException e) {
            // JSON 이 아닌 응답(로그인 화면으로 되돌려보내는 경우)이면 행이 없는 것으로 본다.
            return List.of();
        }
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

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.isValueNode() ? node.asString() : node.toString();
    }

    private static String required(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.API_PROBE_INCOMPLETE, key);
        }
        return value;
    }
}
