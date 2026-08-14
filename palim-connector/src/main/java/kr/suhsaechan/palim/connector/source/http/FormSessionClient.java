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
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
        log.debug("로그인 화면 열기 — 주소={}, 토큰필드={}", loginUrl, tokenField);
        ResponseEntity<String> response =
                restClient.get().uri(loginUrl).retrieve().toEntity(String.class);
        collectCookies(response.getHeaders(), cookies);
        // 상대 화면이 바뀌면 조용히 깨지는 경로라 본문을 통째로 남겨 둔다.
        log.debug("로그인 화면 응답 — 주소={}, 상태={}, 본문길이={}, 쿠키={}, 본문={}",
                loginUrl, response.getStatusCode(), bodyLength(response.getBody()),
                cookies.keySet(), response.getBody());

        String token = findHidden(response.getBody(), tokenField);
        if (token.isEmpty()) {
            log.warn("로그인 폼에서 토큰을 찾지 못했습니다 — 주소={}, 토큰필드={} (토큰 없는 화면일 수 있어 그대로 진행)",
                    loginUrl, tokenField);
        } else {
            // 토큰 값 자체는 남기지 않는다 — 확보 여부만 알면 된다.
            log.debug("로그인 폼 토큰 확보 — 주소={}, 토큰필드={}, 토큰길이={}",
                    loginUrl, tokenField, token.length());
        }
        return token;
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

        // 폼 값에는 비밀번호가 들어 있어 필드 이름만 남긴다.
        log.debug("로그인 폼 전송 — 주소={}, 프리셋={}, 계정={}, 토큰필드={}, 토큰있음={}, 폼필드={}, 보낼쿠키={}",
                loginUrl, config.getOrDefault("preset", "-"), userId, tokenField,
                !token.isEmpty(), form.keySet(), cookies.keySet());

        ResponseEntity<String> response = restClient.post().uri(loginUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.COOKIE, cookieHeader(cookies))
                .body(form)
                .retrieve().toEntity(String.class);
        collectCookies(response.getHeaders(), cookies);
        log.debug("로그인 응답 — 주소={}, 상태={}, 본문길이={}, 본문={}",
                loginUrl, response.getStatusCode(), bodyLength(response.getBody()),
                response.getBody());

        if (!hasSession(cookies)) {
            // 실패해도 200 을 돌려주는 화면이 많아, 판단 근거인 쿠키 목록과 본문을 함께 남긴다.
            log.error("로그인 실패: 세션 쿠키를 받지 못했습니다 — 주소={}, 계정={}, 상태={}, 받은쿠키={}, 본문={}",
                    loginUrl, userId, response.getStatusCode(), cookies.keySet(),
                    response.getBody());
            throw new BusinessException(ErrorCode.API_PROBE_FAILED,
                    "세션을 받지 못했습니다. 계정 정보를 확인하세요.")
                    .withDetails(Map.of(EcountSessionClient.RAW_RESPONSE,
                            String.valueOf(response.getBody())));
        }
        // 쿠키 값에는 세션 식별자가 들어 있어 이름만 남긴다.
        log.info("로그인 성공 — 주소={}, 계정={}, 쿠키 {}건={}",
                loginUrl, userId, cookies.size(), cookies.keySet());
        return new Session(cookies);
    }

    /** 화면이 호출하는 조회 요청을 같은 형식으로 보낸다. */
    public List<Map<String, String>> fetch(Map<String, String> config, Session session) {
        String fetchUrl = required(config, "fetchUrl");
        String body = config.getOrDefault("fetchBody", "");
        log.debug("조회 요청 — 주소={}, 프리셋={}, 행경로={}, 요청본문={}, 보낼쿠키={}",
                fetchUrl, config.getOrDefault("preset", "-"),
                config.getOrDefault("rowsPath", "rows"), body, session.cookies().keySet());

        ResponseEntity<String> response = restClient.post().uri(fetchUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.COOKIE, cookieHeader(session.cookies()))
                .body(body)
                .retrieve().toEntity(String.class);
        // 상대 화면이 바뀌면 응답 모양만 바뀌고 상태 코드는 200 이라 본문을 통째로 남긴다.
        log.debug("조회 응답 — 주소={}, 상태={}, 본문길이={}, 본문={}",
                fetchUrl, response.getStatusCode(), bodyLength(response.getBody()),
                response.getBody());

        List<Map<String, String>> rows =
                parseRows(response.getBody(), config.getOrDefault("rowsPath", "rows"));
        if (rows.isEmpty()) {
            log.error("조회 실패: 응답에서 행을 찾지 못했습니다 — 주소={}, 행경로={}, 상태={}, 본문={}",
                    fetchUrl, config.getOrDefault("rowsPath", "rows"),
                    response.getStatusCode(), response.getBody());
            throw new BusinessException(ErrorCode.API_PROBE_FAILED,
                    "응답에서 행을 찾지 못했습니다. 상대 화면이 바뀌었을 수 있습니다.")
                    .withDetails(Map.of(EcountSessionClient.RAW_RESPONSE,
                            String.valueOf(response.getBody())));
        }
        log.info("조회 완료 — 주소={}, 행 {}건, 컬럼={}",
                fetchUrl, rows.size(), rows.get(0).keySet());
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
            log.debug("응답에 Set-Cookie 헤더가 없습니다 — 지금까지 모은 쿠키={}", cookies.keySet());
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
            log.warn("응답 본문이 비어 있어 행을 읽지 않습니다 — 행경로={}", rowsPath);
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode node = root.path(rowsPath);
            if (!node.isArray()) {
                log.debug("지정한 행경로에 배열이 없어 응답을 훑어 배열을 찾습니다 — 행경로={}", rowsPath);
                node = firstArray(root, 0);
            }
            if (node == null || !node.isArray()) {
                log.warn("응답에서 행 배열을 찾지 못했습니다 — 행경로={}, 본문길이={}",
                        rowsPath, body.length());
                return List.of();
            }
            log.debug("행 배열 확보 — 행경로={}, 항목={}건", rowsPath, node.size());

            List<Map<String, String>> rows = new ArrayList<>();
            int index = 0;
            for (JsonNode item : node) {
                Map<String, String> row = new LinkedHashMap<>();
                if (item.isObject()) {
                    item.properties().forEach(entry ->
                            row.put(entry.getKey(), text(entry.getValue())));
                }
                if (!row.isEmpty()) {
                    rows.add(row);
                } else {
                    // 어느 행이 빠졌는지 알아야 원본과 대조할 수 있다.
                    log.debug("행 건너뜀(객체가 아니거나 값이 없음) — 번호={}, 값={}", index, item);
                }
                index++;
            }
            if (rows.size() < index) {
                log.warn("항목 {}건 중 {}건을 건너뛰었습니다 — 행경로={}",
                        index, index - rows.size(), rowsPath);
            }
            log.debug("행 파싱 완료 — 행경로={}, 행 {}건", rowsPath, rows.size());
            return rows;
        } catch (RuntimeException e) {
            // JSON 이 아닌 응답(로그인 화면으로 되돌려보내는 경우)이면 행이 없는 것으로 본다.
            log.warn("응답을 JSON 으로 읽지 못했습니다 — 행경로={}, 본문길이={}, 본문={}",
                    rowsPath, body.length(), body, e);
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

    /** 로그용 본문 길이. 본문이 없을 때와 빈 문자열일 때를 구분하려고 -1 을 쓴다. */
    private static int bodyLength(String body) {
        return body == null ? -1 : body.length();
    }

    private static String required(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.API_PROBE_INCOMPLETE, key);
        }
        return value;
    }
}
