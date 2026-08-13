package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMap;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMapRepository;
import kr.suhsaechan.palim.connector.define.ConnectorMapping;
import kr.suhsaechan.palim.connector.define.ConnectorMappingRepository;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.connector.run.ConnectorRun;
import kr.suhsaechan.palim.connector.run.ConnectorRunner;
import kr.suhsaechan.palim.connector.run.RunMode;
import kr.suhsaechan.palim.connector.run.RunRequest;
import kr.suhsaechan.palim.connector.run.RunTrigger;
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
 * API 커넥터가 <b>파일 없이</b> 실행되는가.
 *
 * <p>실행 요청이 파일을 필수로 받고 있어서, API 로 연결해도 시험 실행조차 할 수 없었다.
 * 사용자는 이미 받아온 자료를 엑셀로 다시 만들어 올려야 했고, 그러면 매일 자동 수집은
 * 원리적으로 불가능하다 — 사람이 매일 엑셀을 만들 수는 없기 때문이다.
 *
 * <p>시험 실행은 스테이징에만 쓴다. 확인하지 않은 자료가 운영 테이블에 들어가면 그 뒤 대조가
 * 전부 그 값을 기준으로 돌고, 그때는 되돌릴 방법이 없다.
 */
@AutoConfigureMockMvc
class ApiConnectorRunIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ConnectorRunner runner;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private ConnectorMappingRepository mappingRepository;
    @Autowired private ConnectorFieldMapRepository fieldMapRepository;
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

        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT, "std_stock_snapshot").orElseThrow();
        String code = "apirun-" + UUID.randomUUID().toString().substring(0, 8);
        connector = connectorRepository.save(
                Connector.of(TENANT, code, "API 커넥터", model.getId(), SourceType.HTTP_API, "EA"));

        String ref = ConnectorSecretService.refOf(code);
        secretService.put(ref, "apiKey", "dummy-key");

        String local = "http://127.0.0.1:" + server.getAddress().getPort();
        connector.configureSource(Map.of(
                "preset", "ECOUNT", "sandbox", true,
                "companyCode", "123456", "userId", "tester",
                "zoneUrl", local + "/Zone", "apiBase", local), ref);
        connectorRepository.save(connector);

        ConnectorMapping mapping = mappingRepository.save(ConnectorMapping.draft(
                TENANT, connector.getId(), 1,
                Map.of("fields", List.of("PROD_CD", "BAL_QTY"))));

        // 원천에 없는 값은 고정값으로 채운다 — 출처·기준시각·단위가 그렇다.
        String source = "SRC-" + UUID.randomUUID().toString().substring(0, 8);
        fieldMapRepository.saveAll(List.of(
                ConnectorFieldMap.of(TENANT, mapping.getId(), "PROD_CD", "item_ref", Map.of(), 1),
                ConnectorFieldMap.of(TENANT, mapping.getId(), "BAL_QTY", "quantity", Map.of(), 2),
                ConnectorFieldMap.of(TENANT, mapping.getId(), "", "source",
                        Map.of("type", "CONSTANT", "params", Map.of("value", source)), 3),
                ConnectorFieldMap.of(TENANT, mapping.getId(), "", "base_at",
                        Map.of("type", "CONSTANT",
                                "params", Map.of("value", "2026-08-13T00:00:00Z")), 4)));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("파일 없이 시험 실행이 돈다 — 매일 자동 수집이 이 경로를 쓴다")
    void 파일_없이_실행된다() {
        // file 이 null 이다. 사람이 올릴 것이 없고, 스케줄러도 올려 줄 수 없다.
        ConnectorRun run = runner.run(
                new RunRequest(connector.getId(), RunMode.TEST, RunTrigger.MANUAL, null, 1));

        assertThat(run.getTotalCount())
                .as("원천에서 받아온 행이 실행에 들어와야 한다")
                .isEqualTo(2);
    }

    /**
     * 시험 결과 화면이 <b>읽히는 표</b>로 그려지는가.
     *
     * <p>저장된 JSON 을 그대로 뿌리면 값이 제대로 들어갔는지 확인하려고 사람이 중괄호를 읽어야
     * 한다. 확인하라고 만든 화면인데 확인할 수 없으면 그 단계는 형식이 되고, 잘못된 자료가
     * 그대로 통과한다.
     */
    @Test
    @WithMockUser
    @DisplayName("시험 결과가 표로 그려지고 아직 안 들어갔다고 알린다")
    void 시험_결과가_표로_그려진다() throws Exception {
        ConnectorRun run = runner.run(
                new RunRequest(connector.getId(), RunMode.TEST, RunTrigger.MANUAL, null, 1));

        mockMvc.perform(get("/connectors/{id}/runs/{runId}", connector.getId(), run.getId()))
                .andExpect(status().isOk())
                // 확인을 대충 하지 않도록 가장 먼저 말한다
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "진짜 자료에는 아직 아무것도 들어가지 않았습니다")))
                // 표준 항목이 칸 제목으로 나온다 — JSON 원문이 아니다
                .andExpect(content().string(org.hamcrest.Matchers.containsString("item_ref")))
                // 소수점이 길게 붙은 값을 정리해 보여준다
                .andExpect(content().string(org.hamcrest.Matchers.containsString("9,451")));
    }
}
