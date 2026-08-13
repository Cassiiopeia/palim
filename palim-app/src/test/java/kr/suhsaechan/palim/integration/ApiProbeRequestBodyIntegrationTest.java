package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.source.http.ApiAuthPreset;
import kr.suhsaechan.palim.connector.source.http.ApiProbeRegistry;
import kr.suhsaechan.palim.connector.source.http.ProbeReport;
import kr.suhsaechan.palim.connector.source.http.ProbeRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 원천 API 요청이 <b>본문을 실제로 실어 보내는가</b>.
 *
 * <p>이 테스트가 있는 이유가 있다. {@code RestClient.builder()} 로 클라이언트를 직접 만들면
 * 애플리케이션에 구성된 JSON 변환기가 붙지 않아 <b>본문이 빈 채로 나간다.</b> 그런데 상대
 * 서버는 "값이 없다"는 정상 응답(200)을 돌려주므로 예외가 나지 않는다. 코드는 성공한 줄 알고,
 * 화면에는 "회사코드를 확인하세요"가 뜬다.
 *
 * <p>그러면 사용자는 맞는 값을 넣고도 원인을 찾을 수 없다. 실제로 그렇게 한 번 겪었고,
 * 요청을 직접 받아 보기 전까지는 아무도 알아채지 못했다.
 */
class ApiProbeRequestBodyIntegrationTest extends IntegrationTest {

    @Autowired private ApiProbeRegistry probes;

    private HttpServer server;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        receivedBodies.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            // 지역 값이 없는 응답. 실제 상대 서버가 빈 본문에 돌려주는 것과 같은 모양이다.
            byte[] body = "{\"Data\":{\"EMPTY_ZONE\":true}}".getBytes(StandardCharsets.UTF_8);
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

    @Test
    @DisplayName("지역 조회 요청에 회사코드가 실려 나간다")
    void 요청_본문이_비어_있지_않다() {
        String local = "http://127.0.0.1:" + server.getAddress().getPort();
        ProbeRequest request = new ProbeRequest(ApiAuthPreset.ECOUNT, true,
                Map.of("companyCode", "677445",
                        "userId", "tester",
                        "zoneUrl", local + "/zone",
                        "apiBase", local),
                "dummy-key");

        ProbeReport report = probes.of(ApiAuthPreset.ECOUNT).probe(request);

        assertThat(report).isNotNull();
        assertThat(receivedBodies)
                .as("요청이 서버에 닿지 않았다면 주소 조립이 어긋난 것이다")
                .isNotEmpty();
        assertThat(receivedBodies.getFirst())
                .as("본문이 비면 상대는 '값 없음'을 200 으로 돌려주고, 예외가 없어 아무도 모른다")
                .contains("COM_CODE")
                .contains("677445");
    }
}
