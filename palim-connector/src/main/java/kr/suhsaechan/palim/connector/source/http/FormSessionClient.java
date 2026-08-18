package kr.suhsaechan.palim.connector.source.http;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
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
 * <p><b>절차는 이 클래스 한 곳에만 둔다.</b> 예전에는 검증 화면({@link FormSessionProbe})이
 * 같은 절차를 따로 구현했는데, 한쪽만 고쳐지면서 <b>검증은 통과하고 적재는 쓰레기를 담는</b>
 * 상태가 됐다. 화면에서 컬럼이 예쁘게 보이므로 사람은 알 방법이 없다. 그래서 합쳤다.
 */
@Slf4j
@Component
public class FormSessionClient {

    /** 로그인 폼에 숨어 있는 토큰. 없는 화면도 있어 못 찾아도 실패로 보지 않는다. */
    private static final Pattern HIDDEN_INPUT = Pattern.compile(
            "<input[^>]*name=[\"']?%s[\"']?[^>]*value=[\"']([^\"']*)[\"']");

    /**
     * 로그인 값을 브라우저에서 암호화해 보내는 화면이 있다. 그 공개키를 화면에서 읽는다.
     *
     * <p>키를 코드에 박지 않는 이유는 상대가 언제든 바꿀 수 있기 때문이다. 박아 두면 키가 바뀐
     * 날 로그인만 조용히 실패하고, 원인은 «계정이 잘못됐나» 부터 뒤지게 된다.
     */
    private static final Pattern RSA_PUBLIC_KEY = Pattern.compile(
            "setPublic\\(\\s*[\"']([0-9a-fA-F]+)[\"']\\s*,\\s*[\"']([0-9a-fA-F]+)[\"']\\s*\\)");

    /** {@code encodeURIComponent} 가 그대로 두는 글자. 화면 JS 와 같은 문자열을 만들어야 한다. */
    private static final String UNRESERVED =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()";

    private static final String SESSION_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int SESSION_ID_LENGTH = 26;

    /** 요청마다 달라져야 하는 값. 캐시를 무력화하려고 화면이 현재 시각을 실어 보낸다. */
    private static final String NOW_MILLIS_PLACEHOLDER = "{nd}";

    /** 폼에 실으면 안 되는 우리 설정 항목. 상대가 모르는 값이 섞이면 거부하는 화면이 있다. */
    private static final List<String> INTERNAL_KEYS = List.of(
            "loginUrl", "fetchUrl", "fetchBody", "rowsPath", "preset", "sandbox",
            "loginProcessUrl", "recordsPath", "userId", "baseDate");

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

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
     * 로그인 화면에서 얻은 것.
     *
     * <p>토큰도 공개키도 <b>있을 수도 없을 수도</b> 있다. 어느 쪽을 쓰는지는 화면마다 다르므로
     * 둘 다 담아 두고, 보낼 때 골라 쓴다.
     */
    public record LoginPage(String token, String publicKeyModulus, String publicKeyExponent) {

        public boolean hasPublicKey() {
            return !publicKeyModulus.isBlank() && !publicKeyExponent.isBlank();
        }
    }

    /**
     * 로그인 화면을 열어 토큰·공개키·첫 쿠키를 받는다.
     *
     * <p>토큰이 없는 화면도 있다. 없다고 실패로 보면 그런 시스템은 아예 붙일 수 없다.
     */
    public LoginPage openLoginPage(String loginUrl, String tokenField,
                                   Map<String, String> cookies) {
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
            log.debug("로그인 폼에서 토큰을 찾지 못했습니다 — 주소={}, 토큰필드={} (토큰 없는 화면일 수 있어 그대로 진행)",
                    loginUrl, tokenField);
        } else {
            // 토큰 값 자체는 남기지 않는다 — 확보 여부만 알면 된다.
            log.debug("로그인 폼 토큰 확보 — 주소={}, 토큰필드={}, 토큰길이={}",
                    loginUrl, tokenField, token.length());
        }

        String modulus = "";
        String exponent = "";
        Matcher key = RSA_PUBLIC_KEY.matcher(String.valueOf(response.getBody()));
        if (key.find()) {
            modulus = key.group(1);
            exponent = key.group(2);
            log.debug("로그인 화면에서 공개키 확보 — 주소={}, 계수길이={}자리", loginUrl, modulus.length());
        }
        return new LoginPage(token, modulus, exponent);
    }

    /**
     * 폼을 보내 세션을 받는다.
     *
     * <p>보내는 방법이 두 가지다. 평범한 화면은 입력값을 그대로 폼으로 보내고, 어떤 화면은
     * <b>폼 전체를 공개키로 암호화해</b> 한 칸에 담아 다른 주소로 보낸다. 뒤쪽을 모르면
     * 로그인이 통째로 실패하는데, 상대는 200 과 함께 «연결에 실패하였습니다» 만 돌려주므로
     * 화면만 봐서는 계정을 의심하게 된다.
     *
     * <p>성공 판정은 <b>세션 쿠키가 생겼는지</b>로 한다. 실패해도 200 을 돌려주는 화면이 많아
     * 상태 코드로는 가릴 수 없다.
     */
    public Session login(Map<String, String> config, String userId, String password,
                         Map<String, String> cookies, LoginPage page) {
        String loginUrl = required(config, "loginUrl");
        // 암호화해 보내는 화면은 받는 주소가 따로 있다.
        String postUrl = config.getOrDefault("loginProcessUrl", "");
        boolean encrypted = !postUrl.isBlank();
        if (!encrypted) {
            postUrl = loginUrl;
        }

        List<Map.Entry<String, String>> fields = loginFields(config, userId, password, page);
        // 폼 값에는 비밀번호가 들어 있어 필드 이름만 남긴다.
        log.debug("로그인 전송 — 주소={}, 프리셋={}, 계정={}, 암호화={}, 폼필드={}, 보낼쿠키={}",
                postUrl, config.getOrDefault("preset", "-"), userId, encrypted,
                fields.stream().map(Map.Entry::getKey).toList(), cookies.keySet());

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (encrypted) {
            if (!page.hasPublicKey()) {
                log.error("암호화 로그인인데 화면에서 공개키를 찾지 못했습니다 — 주소={}", loginUrl);
                throw new BusinessException(ErrorCode.API_PROBE_FAILED,
                        "로그인 화면에서 공개키를 찾지 못했습니다. 상대 화면이 바뀌었을 수 있습니다.");
            }
            String serialized = serialize(fields);
            form.set(config.getOrDefault("encryptField", "encpar"),
                    encryptHex(serialized, page.publicKeyModulus(), page.publicKeyExponent()));
        } else {
            fields.forEach(entry -> form.set(entry.getKey(), entry.getValue()));
        }

        ResponseEntity<String> response = restClient.post().uri(postUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.COOKIE, cookieHeader(cookies))
                .body(form)
                .retrieve().toEntity(String.class);
        collectCookies(response.getHeaders(), cookies);
        log.debug("로그인 응답 — 주소={}, 상태={}, 본문길이={}",
                postUrl, response.getStatusCode(), bodyLength(response.getBody()));

        if (!hasSession(cookies)) {
            // 실패해도 200 을 돌려주는 화면이 많아, 판단 근거인 쿠키 목록과 본문을 함께 남긴다.
            log.error("로그인 실패: 세션 쿠키를 받지 못했습니다 — 주소={}, 계정={}, 상태={}, 받은쿠키={}, 본문={}",
                    postUrl, userId, response.getStatusCode(), cookies.keySet(),
                    response.getBody());
            throw new BusinessException(ErrorCode.API_PROBE_FAILED,
                    "세션을 받지 못했습니다. 계정 정보를 확인하세요.")
                    .withDetails(Map.of(EcountSessionClient.RAW_RESPONSE,
                            String.valueOf(response.getBody())));
        }
        // 쿠키 값에는 세션 식별자가 들어 있어 이름만 남긴다.
        log.info("로그인 성공 — 주소={}, 계정={}, 쿠키 {}건={}",
                postUrl, userId, cookies.size(), cookies.keySet());
        return new Session(cookies);
    }

    /**
     * 로그인 폼에 실을 값.
     *
     * <p><b>빈 값은 뺀다.</b> 암호화해 보내는 화면은 공개키 크기가 곧 평문 한도라(1024비트면
     * 117바이트) 빈 칸까지 실으면 한도를 넘겨 암호화 자체가 실패한다. 빈 칸은 상대에게도
     * 의미가 없으므로 빼는 편이 양쪽 모두에 맞다.
     */
    private List<Map.Entry<String, String>> loginFields(Map<String, String> config,
                                                        String userId, String password,
                                                        LoginPage page) {
        Map<String, String> values = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            if (key.endsWith("Field") || INTERNAL_KEYS.contains(key)) {
                return;
            }
            values.put(key, value);
        });
        values.put(config.getOrDefault("useridField", "userid"), userId);
        values.put(config.getOrDefault("passwordField", "passwd"), password);
        if (!page.token().isEmpty()) {
            values.put(config.getOrDefault("tokenField", "token"), page.token());
        }
        // 화면이 매번 새로 만들어 보내는 창 식별자. 요구하는 시스템이 있다.
        String sessionIdField = config.getOrDefault("sessionIdField", "");
        if (!sessionIdField.isBlank()) {
            values.put(sessionIdField, randomSessionId());
        }
        return values.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** 화면 JS 의 {@code $(form).serialize()} 와 같은 문자열을 만든다. */
    private static String serialize(List<Map.Entry<String, String>> fields) {
        return fields.stream()
                .map(entry -> encodeComponent(entry.getKey()) + "=" + encodeComponent(entry.getValue()))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
    }

    /** {@code encodeURIComponent} 와 같은 규칙. {@code URLEncoder} 는 공백을 {@code +} 로 바꿔 다르다. */
    private static String encodeComponent(String value) {
        StringBuilder out = new StringBuilder();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int b = raw & 0xFF;
            if (b < 0x80 && UNRESERVED.indexOf((char) b) >= 0) {
                out.append((char) b);
            } else {
                out.append('%').append(HexFormat.of().withUpperCase().toHexDigits((byte) b));
            }
        }
        return out.toString();
    }

    /**
     * 공개키로 암호화해 16진 문자열로 만든다.
     *
     * <p>브라우저가 쓰는 jsbn 라이브러리와 같은 방식(PKCS#1 v1.5)이라 별도 라이브러리가 필요 없다.
     */
    private static String encryptHex(String plain, String modulusHex, String exponentHex) {
        try {
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                    new BigInteger(modulusHex, 16), new BigInteger(exponentHex, 16)));
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return HexFormat.of().formatHex(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // 계정 정보가 길면 한도를 넘는다. «왜 갑자기 안 되지» 로 헤매지 않도록 이유를 적는다.
            log.error("로그인 값을 암호화하지 못했습니다 — 평문 {}바이트, 키 {}자리",
                    plain.getBytes(StandardCharsets.UTF_8).length, modulusHex.length(), e);
            throw new BusinessException(ErrorCode.API_PROBE_FAILED,
                    "로그인 값을 암호화하지 못했습니다. 계정 정보가 너무 길지 않은지 확인하세요.");
        }
    }

    private String randomSessionId() {
        StringBuilder out = new StringBuilder(SESSION_ID_LENGTH);
        for (int i = 0; i < SESSION_ID_LENGTH; i++) {
            out.append(SESSION_ID_CHARS.charAt(random.nextInt(SESSION_ID_CHARS.length())));
        }
        return out.toString();
    }

    /** 화면이 호출하는 조회 요청을 같은 형식으로 보낸다. */
    /**
     * 로그인한 채로 화면 하나를 <b>그대로</b> 받아 온다.
     *
     * <p>자료를 읽으려는 것이 아니라 <b>사람이 볼 안내를 만들려고</b> 쓴다 — 「어느 메뉴로 들어가
     * 엑셀을 받나」 는 로그인해야 보이고, 그걸 코드에 적어 두면 상대가 메뉴를 바꾼 날 안내가
     * 거짓말이 된다.
     *
     * <p>계정은 <b>서버 밖으로 나가지 않는다.</b> 이 호출도 매일 도는 수집과 같은 자리에서
     * 같은 계정으로 이뤄진다.
     */
    public String fetchPage(String url, Session session) {
        ResponseEntity<String> response = restClient.get().uri(url)
                .header(HttpHeaders.COOKIE, cookieHeader(session.cookies()))
                .retrieve().toEntity(String.class);
        log.debug("화면 받기 — 주소={}, 상태={}, 본문길이={}",
                url, response.getStatusCode(), bodyLength(response.getBody()));
        return response.getBody() == null ? "" : response.getBody();
    }

    public List<Map<String, String>> fetch(Map<String, String> config, Session session) {
        String fetchUrl = required(config, "fetchUrl");
        String body = withNow(config.getOrDefault("fetchBody", ""));
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
        warnIfTruncated(response.getBody(), config, rows.size());
        log.info("조회 완료 — 주소={}, 행 {}건, 컬럼={}",
                fetchUrl, rows.size(), rows.get(0).keySet());
        return rows;
    }

    /** 요청 본문의 자리표시자를 지금 시각으로 바꾼다. 화면이 캐시를 피하려고 넣는 값이다. */
    private static String withNow(String body) {
        return body.contains(NOW_MILLIS_PLACEHOLDER)
                ? body.replace(NOW_MILLIS_PLACEHOLDER, String.valueOf(System.currentTimeMillis()))
                : body;
    }

    /**
     * 받은 행이 상대가 말한 전체 건수보다 적으면 알린다.
     *
     * <p>한 번에 받는 행 수에는 한도가 있다. 넘치면 상대는 <b>성공으로 답하고 앞부분만</b> 준다.
     * 그대로 두면 재고가 늘어난 어느 날 대조가 조용히 일부만 보고 «차이 없음» 이라고 말한다.
     */
    private void warnIfTruncated(String body, Map<String, String> config, int received) {
        String path = config.getOrDefault("recordsPath", "records");
        if (body == null || path.isBlank()) {
            return;
        }
        try {
            JsonNode records = mapper.readTree(body).path(path);
            if (records.isNumber() && records.asInt() > received) {
                log.error("받은 행이 전체보다 적습니다 — 받음 {}행, 상대가 말한 전체 {}행. "
                                + "한 번에 받는 행 수(fetchBody 의 rows)를 늘려야 합니다",
                        received, records.asInt());
            }
        } catch (RuntimeException e) {
            log.debug("전체 건수를 읽지 못했습니다 — 경로={}", path);
        }
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
     *
     * <p><b>그리드 응답은 값이 한 겹 더 들어가 있다</b> ({@code {"id":0,"cell":{…}}}). 벗기지 않으면
     * 칸이 {@code id}·{@code cell} 두 개로 보이고 값은 통째로 한 덩어리가 되어 매핑할 수가 없다.
     * 그런데도 수집은 «성공» 으로 기록되므로 사람은 며칠 뒤에야 안다.
     *
     * <p>값에 HTML 태그가 섞여 오는 칸도 있다 ({@code <a href=…>425</a>}). 화면에 뿌리려고 만든
     * 응답이라 그렇다. 태그를 벗겨야 숫자로 쓸 수 있다.
     */
    public List<Map<String, String>> parseRows(String body, String rowsPath) {
        if (body == null || body.isBlank()) {
            log.warn("응답 본문이 비어 있어 행을 읽지 않습니다 — 행경로={}", rowsPath);
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode node = root;
            // 경로는 «a.b.c» 처럼 중첩을 허용한다. 한 칸짜리면 그냥 그 칸이다.
            for (String part : rowsPath.split("\\.")) {
                node = node.path(part);
            }
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
                JsonNode target = item.has("cell") ? item.get("cell") : item;
                Map<String, String> row = new LinkedHashMap<>();
                if (target.isObject()) {
                    target.properties().forEach(entry ->
                            row.put(entry.getKey(), stripTags(entry.getValue())));
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

    /** 화면용 응답은 값에 HTML 태그가 섞여 온다. 그대로 적재하면 매핑이 쓸모없어진다. */
    private static String stripTags(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        String raw = node.isValueNode() ? node.asString() : node.toString();
        return raw.replaceAll("<[^>]*>", "").trim();
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
