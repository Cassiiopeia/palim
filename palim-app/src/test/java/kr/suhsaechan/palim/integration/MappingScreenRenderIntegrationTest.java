package kr.suhsaechan.palim.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.connector.secret.ConnectorSecretService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 칸 연결 화면이 <b>실제로 그려지는가</b>.
 *
 * <p>템플릿 표현식 오류는 컴파일에 걸리지 않는다. 문자열을 불린 자리에 쓰거나 없는 속성을
 * 참조해도 빌드는 통과하고 <b>화면을 여는 순간</b> 터진다. 그때는 이미 배포된 뒤다.
 *
 * <p>내용까지 확인하는 이유는 이 화면의 존재 이유가 «값이 보이는 것» 이기 때문이다. 값이 빠진
 * 화면은 그려져도 쓸모가 없다.
 */
@AutoConfigureMockMvc
class MappingScreenRenderIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private TargetModelRepository targetModelRepository;
    @Autowired private ConnectorSecretService secretService;

    private HttpServer server;
    private Connector connector;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String json;
            if (path.endsWith("/Zone")) {
                json = "{\"Data\":{\"ZONE\":\"AD\"}}";
            } else if (path.endsWith("/OAPILogin")) {
                json = "{\"Data\":{\"Datas\":{\"SESSION_ID\":\"sess-1\"}}}";
            } else {
                json = "{\"Data\":{\"Result\":["
                        + "{\"WH_CD\":\"200\",\"PROD_CD\":\"A0001\",\"BAL_QTY\":\"9451.0000000000\","
                        + "\"REMARK\":\"비고\"}]}}";
            }
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT, "std_stock_snapshot").orElseThrow();
        String code = "render-" + UUID.randomUUID().toString().substring(0, 8);
        connector = connectorRepository.save(
                Connector.of(TENANT, code, "화면 확인용", model.getId(), SourceType.HTTP_API, "EA"));

        String ref = ConnectorSecretService.refOf(code);
        secretService.put(ref, "apiKey", "dummy-key");

        String local = "http://127.0.0.1:" + server.getAddress().getPort();
        connector.configureSource(Map.of(
                "preset", "ECOUNT", "sandbox", true,
                "companyCode", "123456", "userId", "tester",
                "zoneUrl", local + "/Zone", "apiBase", local), ref);
        connectorRepository.save(connector);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @WithMockUser
    @DisplayName("화면이 그려지고 실제 값과 추천이 함께 보인다")
    void 화면이_그려진다() throws Exception {
        mockMvc.perform(get("/connectors/{id}/mapping", connector.getId()))
                .andExpect(status().isOk())
                .andExpect(RenderAssertions.fullyRendered())
                // 저쪽 칸이 선택지로 나온다
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BAL_QTY")))
                // 소수점이 길게 붙은 값을 읽을 수 있게 정리한다
                .andExpect(content().string(org.hamcrest.Matchers.containsString("9,451")))
                // 저쪽에 있을 수 없는 항목은 고르게 하지 않는다
                .andExpect(content().string(org.hamcrest.Matchers.containsString("자동")))
                // 우리 항목에 자리가 없는 칸도 숨기지 않는다
                .andExpect(content().string(org.hamcrest.Matchers.containsString("REMARK")))
                // 파일 원천이 아니므로 업로드 단계가 없어야 한다
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("원천 파일 올리기"))));
    }
}
