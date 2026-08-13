package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
import kr.suhsaechan.palim.connector.run.ConnectorRunRepository;
import kr.suhsaechan.palim.connector.run.ConnectorScheduler;
import kr.suhsaechan.palim.connector.secret.ConnectorSecretService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 매일 자동으로 받아오는가.
 *
 * <p>여기까지 와야 연동이 <b>업무가 된다.</b> 사람이 화면에 들어와 버튼을 눌러야 자료가 쌓이는
 * 구조라면 바쁜 날 거르고, 그러다 안 하게 된다.
 *
 * <p>판정이 틀리면 두 가지로 망가진다 — 안 돌거나, 중복으로 돈다. 뒤쪽이 더 나쁘다. 같은 자료가
 * 두 벌 담기면 대조가 어긋나고, 그 사실을 한참 뒤에 안다.
 */
class ConnectorSchedulerIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private ConnectorScheduler scheduler;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private ConnectorMappingRepository mappingRepository;
    @Autowired private ConnectorFieldMapRepository fieldMapRepository;
    @Autowired private TargetModelRepository targetModelRepository;
    @Autowired private ConnectorSecretService secretService;
    @Autowired private ConnectorRunRepository runRepository;

    private HttpServer server;
    private TargetModel model;

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
                json = "{\"Data\":{\"Result\":[{\"PROD_CD\":\"A0001\",\"BAL_QTY\":\"112\"}]}}";
            }
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        model = targetModelRepository.findByTenantIdAndCode(TENANT, "std_stock_snapshot")
                .orElseThrow();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 매분 도는 표현식을 준다. 확인 즉시 «지금 돌 차례» 가 되도록. */
    private Connector connector(String cron, boolean activateMapping) {
        String code = "sched-" + UUID.randomUUID().toString().substring(0, 8);
        Connector connector = connectorRepository.save(
                Connector.of(TENANT, code, "예약 커넥터", model.getId(), SourceType.HTTP_API, "EA"));

        String ref = ConnectorSecretService.refOf(code);
        secretService.put(ref, "apiKey", "dummy-key");

        String local = "http://127.0.0.1:" + server.getAddress().getPort();
        connector.configureSource(Map.of(
                "preset", "ECOUNT", "sandbox", true,
                "companyCode", "123456", "userId", "tester",
                "zoneUrl", local + "/Zone", "apiBase", local), ref);
        connector.schedule(cron);
        connectorRepository.save(connector);

        ConnectorMapping mapping = ConnectorMapping.draft(TENANT, connector.getId(), 1,
                Map.of("fields", List.of("PROD_CD", "BAL_QTY")));
        if (activateMapping) {
            mapping.activate();
        }
        mappingRepository.save(mapping);

        String source = "SRC-" + UUID.randomUUID().toString().substring(0, 8);
        fieldMapRepository.saveAll(List.of(
                ConnectorFieldMap.of(TENANT, mapping.getId(), "PROD_CD", "item_ref", Map.of(), 1),
                ConnectorFieldMap.of(TENANT, mapping.getId(), "BAL_QTY", "quantity", Map.of(), 2),
                ConnectorFieldMap.of(TENANT, mapping.getId(), "", "source",
                        Map.of("type", "CONSTANT", "params", Map.of("value", source)), 3),
                ConnectorFieldMap.of(TENANT, mapping.getId(), "", "base_at",
                        Map.of("type", "CONSTANT",
                                "params", Map.of("value", "2026-08-13T00:00:00Z")), 4)));
        return connector;
    }

    @Test
    @DisplayName("확정된 연결이 있으면 예정 시각에 자동으로 받아온다")
    void 예정된_수집이_돈다() {
        Connector connector = connector("* * * * * *", true);

        scheduler.runDue();

        assertThat(runRepository.findByConnectorIdOrderByStartedAtDesc(connector.getId()))
                .as("사람이 누르지 않아도 자료가 쌓여야 연동이 업무가 된다")
                .isNotEmpty();
    }

    /**
     * 연결을 짜다 만 상태로 자동 수집이 돌면 절반만 채워진 자료가 쌓이고, 그것을 기준으로
     * 대조가 돈다. 확정 전에는 돌리지 않는다.
     */
    @Test
    @DisplayName("연결을 확정하지 않았으면 돌지 않는다")
    void 확정_전에는_돌지_않는다() {
        Connector connector = connector("* * * * * *", false);

        scheduler.runDue();

        assertThat(runRepository.findByConnectorIdOrderByStartedAtDesc(connector.getId()))
                .as("절반만 채워진 자료가 쌓이면 되돌릴 방법이 없다")
                .isEmpty();
    }

    /**
     * 업로드 원천은 사람이 파일을 올려야 하므로 자동으로 돌 방법이 없다. 그런데도 대상에 넣으면
     * 매분 실패 기록만 쌓여 진짜 문제가 묻힌다.
     */
    @Test
    @DisplayName("파일을 올려야 하는 원천은 대상이 아니다")
    void 업로드_원천은_건너뛴다() {
        String code = "upload-" + UUID.randomUUID().toString().substring(0, 8);
        Connector upload = connectorRepository.save(
                Connector.of(TENANT, code, "업로드", model.getId(), SourceType.UPLOAD, "EA"));
        upload.schedule("* * * * * *");
        connectorRepository.save(upload);

        ConnectorMapping mapping = ConnectorMapping.draft(TENANT, upload.getId(), 1,
                Map.of("fields", List.of("품목")));
        mapping.activate();
        mappingRepository.save(mapping);

        scheduler.runDue();

        assertThat(runRepository.findByConnectorIdOrderByStartedAtDesc(upload.getId()))
                .as("올릴 파일이 없는데 돌면 실패 기록만 쌓인다")
                .isEmpty();
    }
}
