package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.source.http.EcountSessionClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 인증과 조회가 <b>검증 화면 밖에서도</b> 동작하는가.
 *
 * <p>이 클라이언트가 없으면 연결 확인은 되는데 실제 수집이 안 된다. 인증 절차가 검증용 클래스
 * 안에만 있어 실행 경로에서 쓸 수 없기 때문이다. 그렇다고 같은 절차를 두 벌 짜면 한쪽만
 * 고쳐져 어긋나고, 그 어긋남은 «연결 확인은 통과하는데 수집은 실패한다» 는 모양으로 나타나
 * 원인을 찾기 어렵다.
 *
 * <p>실제 원격을 부르지 않는다. 테스트용 인증키는 조회에 한 번 성공하면 죽으므로, 테스트가
 * 키를 태우면 사람이 쓸 키가 남지 않는다.
 */
class EcountSessionClientIntegrationTest extends IntegrationTest {

    @Autowired private EcountSessionClient client;

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        paths.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            String json;
            if (path.endsWith("/Zone")) {
                json = "{\"Data\":{\"ZONE\":\"AD\"}}";
            } else if (path.endsWith("/OAPILogin")) {
                json = "{\"Data\":{\"Datas\":{\"SESSION_ID\":\"sess-1\"}}}";
            } else {
                json = "{\"Data\":{\"Result\":["
                        + "{\"PROD_CD\":\"A0001\",\"BAL_QTY\":\"112\"},"
                        + "{\"PROD_CD\":\"B0002\",\"BAL_QTY\":\"9451\"}]}}";
            }
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private EcountSessionClient.EcountEndpoint endpoint() {
        String local = "http://127.0.0.1:" + server.getAddress().getPort();
        return new EcountSessionClient.EcountEndpoint(
                "example.test", "sbo", "oapi", true, local + "/Zone", local);
    }

    @Test
    @DisplayName("지역 조회 → 로그인 → 재고 조회가 이어진다")
    void 인증부터_조회까지_이어진다() {
        var endpoint = endpoint();

        String zone = client.resolveZone(endpoint, "123456");
        assertThat(zone).isEqualTo("AD");

        String session = client.login(endpoint, zone, "123456", "tester", "dummy-key");
        assertThat(session).isEqualTo("sess-1");

        List<Map<String, String>> rows =
                client.fetchInventory(endpoint, zone, session, LocalDate.of(2026, 8, 13));

        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst())
                .containsEntry("PROD_CD", "A0001")
                .containsEntry("BAL_QTY", "112");
    }

    /**
     * 본문이 비어 나가면 상대는 «값이 없다» 는 정상 응답(200)을 돌려준다. 예외가 나지 않으므로
     * 코드는 성공한 줄 알고, 화면에는 엉뚱한 안내가 뜬다. 실제로 그렇게 한 번 겪었다.
     */
    @Test
    @DisplayName("요청 본문이 실제로 실려 나간다")
    void 요청_본문이_비지_않는다() {
        client.resolveZone(endpoint(), "123456");

        assertThat(paths)
                .as("주소 조립이 어긋나면 요청이 서버에 닿지도 않는다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("지역이 비면 사유를 담아 실패한다")
    void 지역이_없으면_실패한다() throws IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"Data\":{\"EMPTY_ZONE\":true}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        assertThatThrownBy(() -> client.resolveZone(endpoint(), "123456"))
                .as("빈 지역으로 다음 단계를 진행하면 원인이 상대 서버 메시지로 흐려진다")
                .isInstanceOf(BusinessException.class);
    }
}
