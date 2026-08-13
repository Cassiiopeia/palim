package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.connector.secret.ConnectorSecretService;
import kr.suhsaechan.palim.connector.source.SourceContext;
import kr.suhsaechan.palim.connector.source.SourceReaderRegistry;
import kr.suhsaechan.palim.connector.source.SourceSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 로그인 화면을 통과해 받아오는 원천.
 *
 * <p>대조는 <b>두 곳을 비교하는 일</b>이라 한쪽만으로는 시작할 수 없다. 전산(ERP)은 공개 API
 * 가 있지만 물류 쪽은 유료라, 화면이 쓰는 경로를 그대로 쓴다.
 *
 * <p>이 경로는 <b>상대 화면이 바뀌면 깨진다.</b> 그래서 실패를 조용히 삼키면 안 된다 — 빈
 * 목록을 돌려주면 «재고가 0» 으로 읽히고, 그 상태로 대조가 돌면 있지도 않은 차이가 잔뜩 뜬다.
 */
class FormSessionSourceIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private SourceReaderRegistry readers;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private TargetModelRepository targetModelRepository;
    @Autowired private ConnectorSecretService secretService;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 로그인 폼 → 세션 쿠키 → 조회. 실패 상황을 만들려면 sessionOnLogin 을 끈다. */
    private void startServer(boolean sessionOnLogin, String fetchBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String response;

            if (path.endsWith("/login") && "GET".equals(method)) {
                // 로그인 화면에 숨은 토큰이 있다. 이것을 찾아 함께 보내야 통과하는 시스템이 있다.
                response = "<html><form>"
                        + "<input type=\"hidden\" name=\"token\" value=\"tok-123\">"
                        + "</form></html>";
            } else if (path.endsWith("/login")) {
                if (sessionOnLogin) {
                    exchange.getResponseHeaders().add("Set-Cookie", "PHPSESSID=abc; Path=/");
                }
                // 실패해도 200 을 돌려주는 화면이 많다. 상태 코드로는 가릴 수 없다.
                response = "<html>ok</html>";
            } else {
                response = fetchBody;
            }

            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    private SourceContext context() {
        String local = "http://127.0.0.1:" + server.getAddress().getPort();
        String code = "wms-" + UUID.randomUUID().toString().substring(0, 8);
        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT, "std_stock_snapshot").orElseThrow();

        Connector connector = connectorRepository.save(
                Connector.of(TENANT, code, "물류 커넥터", model.getId(), SourceType.HTTP_API, "EA"));

        String ref = ConnectorSecretService.refOf(code);
        secretService.put(ref, "password", "dummy-password");

        connector.configureSource(Map.of(
                "preset", "CUSTOM_FORM",
                "userId", "tester",
                "loginUrl", local + "/login",
                "fetchUrl", local + "/fetch",
                "fetchBody", "action=search",
                "rowsPath", "rows"), ref);
        connectorRepository.save(connector);

        return new SourceContext(connector.getId(), null, 1, null, connector.getSourceConfig());
    }

    @Test
    @DisplayName("로그인 화면을 통과해 재고를 받아온다")
    void 폼_로그인으로_받아온다() throws IOException {
        startServer(true, "{\"rows\":["
                + "{\"ITEM_CD\":\"A0001\",\"QTY\":\"30\"},"
                + "{\"ITEM_CD\":\"B0002\",\"QTY\":\"12\"}]}");

        SourceSchema schema = readers.of(SourceType.HTTP_API).readSchema(context());

        assertThat(schema.fields()).contains("ITEM_CD", "QTY");
        assertThat(schema.sampleRows()).hasSize(2);
        assertThat(schema.sampleRows().getFirst()).containsEntry("QTY", "30");
    }

    /**
     * 로그인이 막혔는데 빈 목록을 돌려주면 «재고가 0» 으로 읽힌다. 그 상태로 대조가 돌면 있지도
     * 않은 차이가 잔뜩 뜨고, 사람은 그것을 실제 불일치로 믿는다.
     */
    @Test
    @DisplayName("로그인이 막히면 조용히 넘어가지 않는다")
    void 로그인_실패를_드러낸다() throws IOException {
        startServer(false, "{\"rows\":[]}");
        SourceContext context = context();

        assertThatThrownBy(() -> readers.of(SourceType.HTTP_API).readSchema(context))
                .as("빈 결과를 주면 재고 0 으로 읽혀 대조가 통째로 어긋난다")
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 상대 화면이 바뀌면 응답 모양이 달라진다. 그때도 실패를 드러내야 한다 — 조용히 멈추면
     * 옛 자료로 대조가 계속 돌고, 사람은 그 결과를 믿고 판단한다.
     */
    @Test
    @DisplayName("응답 모양이 바뀌면 실패로 알린다")
    void 응답이_바뀌면_알린다() throws IOException {
        startServer(true, "<html>로그인이 필요합니다</html>");
        SourceContext context = context();

        assertThatThrownBy(() -> readers.of(SourceType.HTTP_API).readSchema(context))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("전체 행을 흘려 적재와 자동 수집에 쓴다")
    void 전체를_읽는다() throws IOException {
        startServer(true, "{\"rows\":["
                + "{\"ITEM_CD\":\"A0001\",\"QTY\":\"30\"},"
                + "{\"ITEM_CD\":\"B0002\",\"QTY\":\"12\"},"
                + "{\"ITEM_CD\":\"C0003\",\"QTY\":\"7\"}]}");

        List<?> rows = readers.of(SourceType.HTTP_API).read(context()).toList();

        assertThat(rows).hasSize(3);
    }
}
