package kr.suhsaechan.palim.connector.source.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 로그인 화면을 통과해 받아오는 절차.
 *
 * <p>여기서 지키는 것은 두 가지다.
 *
 * <p><b>하나. 그리드 응답을 사람이 쓸 수 있는 모양으로 편다.</b> 화면용 응답은 값이 한 겹 더
 * 들어가 있고({@code cell}) 숫자에도 HTML 태그가 섞여 온다. 벗기지 않으면 칸이 두 개로 보이고
 * 값은 통째로 한 덩어리가 되는데, <b>수집은 「성공」으로 기록된다.</b> 화면에서는 멀쩡해 보여
 * 사람은 며칠 뒤 대조 결과가 이상할 때에야 안다. 실제로 그 상태였다.
 *
 * <p><b>둘. 암호화해 보내는 로그인 화면을 통과한다.</b> 입력값을 그대로 보내면 상대는 200 과
 * 함께 「연결에 실패하였습니다」 를 돌려주고 세션을 주지 않는다. 상태 코드가 정상이라 실패로
 * 보이지도 않는다.
 */
class FormSessionClientTest {

    private final FormSessionClient client = new FormSessionClient();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 그리드 응답의 실제 모양이다. 상대 서버에서 받은 것을 그대로 줄였다.
     *
     * <p>{@code stock_normal} 은 순수 숫자이고 {@code stock_info_st_0_wh_1} 은 같은 값에 링크가
     * 걸려 있다. 둘 다 쓸 수 있어야 한다 — 어느 칸을 쓸지는 사람이 매핑 화면에서 고른다.
     */
    private static final String GRID_RESPONSE = """
            {"rows":[
              {"id":0,"cell":{
                 "key":"00094",
                 "product_name":"제품A 198g (26.11.26)",
                 "stock_normal":"425",
                 "stock_info_st_0_wh_1":"<a class='clickable' href=\\"javascript:detail('00094')\\"><span>425</span></a>",
                 "options":""}}
            ],"records":1,"page":"1","total":1}""";

    @Test
    @DisplayName("그리드 응답의 한 겹을 벗기고 태그를 제거한다")
    void 그리드_응답을_편다() {
        List<Map<String, String>> rows = client.parseRows(GRID_RESPONSE, "rows");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().keySet())
                .as("cell 을 벗기지 않으면 칸이 id·cell 두 개뿐이라 매핑할 것이 없다")
                .containsExactly("key", "product_name", "stock_normal",
                        "stock_info_st_0_wh_1", "options");
        assertThat(rows.getFirst())
                .as("태그가 붙은 칸도 숫자로 쓸 수 있어야 한다 — 안 그러면 수량이 문자열 덩어리가 된다")
                .containsEntry("stock_info_st_0_wh_1", "425")
                .containsEntry("stock_normal", "425")
                .containsEntry("key", "00094");
    }

    /**
     * 암호화해 보내는 화면을 통과하는가.
     *
     * <p>흉내 낸 서버가 <b>실제로 복호화해 값을 확인한다.</b> 「encpar 라는 칸이 있더라」 까지만
     * 보면 엉뚱한 것을 암호화해 보내도 통과해 버린다.
     */
    @Test
    @DisplayName("암호화 로그인 화면이면 폼을 암호화해 별도 주소로 보낸다")
    void 암호화_로그인을_통과한다() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
        AtomicReference<String> decrypted = new AtomicReference<>();
        AtomicReference<String> plainLoginHit = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login.html", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                // 평범한 폼 전송으로 오면 안 된다. 왔다는 사실만 남기고 세션은 주지 않는다.
                plainLoginHit.set(readBody(exchange));
                respond(exchange, "<script>alert('연결에 실패하였습니다.');</script>", null);
                return;
            }
            respond(exchange, """
                    <html><body>
                    <input type="hidden" name="token" value="tok-9">
                    <script>rsa.setPublic("%s", "%s");</script>
                    </body></html>"""
                    .formatted(pub.getModulus().toString(16),
                            pub.getPublicExponent().toString(16)), null);
        });
        server.createContext("/login_process.php", exchange -> {
            Map<String, String> form = parseForm(readBody(exchange));
            try {
                Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                cipher.init(Cipher.DECRYPT_MODE, keys.getPrivate());
                decrypted.set(new String(
                        cipher.doFinal(HexFormat.of().parseHex(form.get("encpar"))),
                        StandardCharsets.UTF_8));
            } catch (Exception e) {
                respond(exchange, "복호화 실패", null);
                return;
            }
            respond(exchange, "<html>ok</html>", "PHPSESSID=abc123; Path=/");
        });
        server.createContext("/function.html", exchange ->
                respond(exchange, GRID_RESPONSE, null));
        server.start();

        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        Map<String, String> config = new LinkedHashMap<>(Map.of(
                "loginUrl", origin + "/login.html",
                "loginProcessUrl", origin + "/login_process.php",
                "fetchUrl", origin + "/function.html",
                "useridField", "userid",
                "passwordField", "passwd",
                "tokenField", "token",
                "encryptField", "encpar",
                "sessionIdField", "tab_id",
                "rowsPath", "rows",
                "domain", "acme"));
        config.put("fetchBody", "template=I100&nd={nd}");

        Map<String, String> cookies = new LinkedHashMap<>();
        FormSessionClient.LoginPage page =
                client.openLoginPage(config.get("loginUrl"), "token", cookies);
        assertThat(page.hasPublicKey())
                .as("화면에서 공개키를 못 찾으면 로그인은 시작조차 할 수 없다")
                .isTrue();
        assertThat(page.token()).isEqualTo("tok-9");

        FormSessionClient.Session session =
                client.login(config, "tester", "pw-secret", cookies, page);

        assertThat(plainLoginHit.get())
                .as("평범한 폼 전송으로 보내면 상대는 세션을 주지 않는다 — 그 길로 가면 안 된다")
                .isNull();
        assertThat(decrypted.get())
                .as("상대가 복호화했을 때 화면이 보내는 것과 같은 값이 나와야 한다")
                .contains("userid=tester")
                .contains("passwd=pw-secret")
                .contains("domain=acme")
                .contains("token=tok-9")
                .contains("tab_id=");
        assertThat(session.cookies()).containsKey("PHPSESSID");

        List<Map<String, String>> rows = client.fetch(config, session);
        assertThat(rows.getFirst())
                .as("로그인만 되고 자료가 덩어리로 들어오면 «연결됐다» 는 말이 의미가 없다")
                .containsEntry("stock_normal", "425");
    }

    @Test
    @DisplayName("요청 본문의 {nd} 자리에 현재 시각이 들어간다")
    void 캐시무력화_값이_채워진다() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/function.html", exchange -> {
            received.set(readBody(exchange));
            respond(exchange, GRID_RESPONSE, null);
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/function.html";
        client.fetch(Map.of("fetchUrl", url, "rowsPath", "rows",
                        "fetchBody", "template=I100&nd={nd}"),
                new FormSessionClient.Session(Map.of()));

        assertThat(received.get())
                .as("자리표시자가 그대로 나가면 상대가 캐시된 옛 응답을 돌려줄 수 있다")
                .doesNotContain("{nd}")
                .matches("template=I100&nd=\\d{13}");
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(pair.substring(0, eq),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    private static void respond(HttpExchange exchange, String body, String setCookie)
            throws IOException {
        if (setCookie != null) {
            exchange.getResponseHeaders().add("Set-Cookie", setCookie);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 1024비트 키의 평문 한도(117바이트)를 넘기면 무슨 일이 생기는지 못 박아 둔다. */
    @Test
    @DisplayName("계정 정보가 너무 길면 이유를 알 수 있는 실패가 난다")
    void 평문_한도를_넘기면_이유가_남는다() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        RSAPublicKey pub = (RSAPublicKey) generator.generateKeyPair().getPublic();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login.html", exchange -> respond(exchange,
                "<script>rsa.setPublic(\"%s\", \"%s\");</script>"
                        .formatted(pub.getModulus().toString(16),
                                pub.getPublicExponent().toString(16)), null));
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();

        Map<String, String> cookies = new LinkedHashMap<>();
        FormSessionClient.LoginPage page =
                client.openLoginPage(origin + "/login.html", "token", cookies);
        Map<String, String> config = Map.of(
                "loginUrl", origin + "/login.html",
                "loginProcessUrl", origin + "/login_process.php",
                "useridField", "userid", "passwordField", "passwd");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                client.login(config, "t".repeat(80), "p".repeat(80), cookies, page)))
                .as("«세션을 못 받았다» 로만 끝나면 계정을 의심하며 시간을 쓴다")
                .isInstanceOf(kr.suhsaechan.palim.common.error.BusinessException.class);
        assertThat(BigInteger.valueOf(pub.getModulus().bitLength())).isEqualTo(BigInteger.valueOf(1024));
    }
}
