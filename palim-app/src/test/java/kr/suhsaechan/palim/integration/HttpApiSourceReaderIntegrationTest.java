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
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.connector.secret.ConnectorSecretService;
import kr.suhsaechan.palim.connector.source.SourceContext;
import kr.suhsaechan.palim.connector.source.SourceReaderRegistry;
import kr.suhsaechan.palim.connector.source.SourceRow;
import kr.suhsaechan.palim.connector.source.SourceSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * API 로 연결한 원천에서 <b>실제로 자료를 읽어 오는가</b>.
 *
 * <p>이 어댑터가 없어서 API 연결이 그 뒤 흐름과 끊겨 있었다. 연결 확인은 통과하는데 매핑 화면은
 * 엑셀을 올리라 하고, 실행도 파일 업로드뿐이라 <b>매일 자동으로 받아올 경로가 아예 없었다.</b>
 *
 * <p>{@code readSchema} 와 {@code read} 를 나눠 확인하는 이유는 쓰임이 다르기 때문이다 —
 * 매핑 화면은 칸 이름과 샘플 몇 행이면 되고, 적재는 전체를 흘려야 한다. 하나로 합치면 화면을
 * 열 때마다 전체를 받는다.
 */
class HttpApiSourceReaderIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private SourceReaderRegistry readers;
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
                        + "{\"WH_CD\":\"200\",\"PROD_CD\":\"A0001\",\"BAL_QTY\":\"112\"},"
                        + "{\"WH_CD\":\"300\",\"PROD_CD\":\"A0001\",\"BAL_QTY\":\"9451\"},"
                        + "{\"WH_CD\":\"200\",\"PROD_CD\":\"B0002\",\"BAL_QTY\":\"4\"}]}}";
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
        String code = "api-" + UUID.randomUUID().toString().substring(0, 8);
        connector = connectorRepository.save(
                Connector.of(TENANT, code, "API 커넥터", model.getId(), SourceType.HTTP_API, "EA"));

        // 비밀값은 설정에 넣지 않는다. 참조만 커넥터에 두고 값은 암호화 저장소에서 꺼낸다.
        String ref = ConnectorSecretService.refOf(code);
        secretService.put(ref, "apiKey", "dummy-key");

        String local = "http://127.0.0.1:" + server.getAddress().getPort();
        connector.configureSource(Map.of(
                "preset", "ECOUNT",
                "sandbox", true,
                "companyCode", "123456",
                "userId", "tester",
                "zoneUrl", local + "/Zone",
                "apiBase", local), ref);
        connectorRepository.save(connector);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private SourceContext context() {
        return new SourceContext(connector.getId(), null, 1, null, connector.getSourceConfig());
    }

    @Test
    @DisplayName("칸 목록과 샘플을 읽는다 — 매핑 화면이 파일 없이 열린다")
    void 스키마를_읽는다() {
        SourceSchema schema = readers.of(SourceType.HTTP_API).readSchema(context());

        assertThat(schema.fields())
                .as("이 칸 이름들이 매핑 화면의 선택지가 된다")
                .contains("WH_CD", "PROD_CD", "BAL_QTY");
        assertThat(schema.sampleRows())
                .as("값을 보여줘야 무엇을 고를지 판단할 수 있다")
                .isNotEmpty();
        assertThat(schema.sampleRows().getFirst()).containsEntry("BAL_QTY", "112");
    }

    @Test
    @DisplayName("전체 행을 흘린다 — 적재와 매일 자동 수집이 이 경로로 돈다")
    void 전체를_읽는다() {
        List<SourceRow> rows = readers.of(SourceType.HTTP_API).read(context()).toList();

        assertThat(rows).hasSize(3);
        assertThat(rows.getFirst().rowNumber())
                .as("실패한 줄을 사람에게 알리려면 줄 번호가 1부터여야 한다")
                .isEqualTo(1);
        assertThat(rows.get(1).values()).containsEntry("BAL_QTY", "9451");
    }

    @Test
    @DisplayName("레지스트리가 API 어댑터를 찾는다")
    void 레지스트리에_등록된다() {
        assertThat(readers.of(SourceType.HTTP_API))
                .as("등록되지 않으면 실행 오케스트레이터가 원천에 닿지 못한다")
                .isNotNull();
    }
}
