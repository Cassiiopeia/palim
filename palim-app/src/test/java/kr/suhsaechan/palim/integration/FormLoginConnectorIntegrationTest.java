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
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMap;
import kr.suhsaechan.palim.connector.define.ConnectorFieldMapRepository;
import kr.suhsaechan.palim.connector.define.ConnectorMapping;
import kr.suhsaechan.palim.connector.define.ConnectorMappingRepository;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
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
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 웹 로그인으로 붙는 원천이 <b>끝까지 도는가</b>.
 *
 * <p>이카운트는 인증키를 주고받는 방식이지만, 물류 쪽은 사람이 화면에 로그인할 때 쓰는
 * 계정으로 붙는다 — 로그인 화면을 열어 토큰을 얻고, 그 토큰으로 로그인해 세션 쿠키를 받고,
 * 그 쿠키로 조회한다. <b>단계가 셋이고 각각 다른 이유로 실패한다.</b>
 *
 * <p>실제 상대 서버를 부르지 않고 흉내 낸 서버로 확인한다. 진짜 계정으로 시험하면 남의 운영
 * 시스템에 로그인 기록이 쌓이고, 무엇보다 <b>붙여 보기 전에 코드가 되는지 알아야</b> 한다.
 *
 * <p>이 테스트가 지키는 또 하나는 <b>고정값 없이 돈다</b>는 것이다. 출처·기준 시각은 상대가
 * 보내주는 값이 아니라 우리가 아는 값이라 시스템이 채운다. 예전에는 그것을 사람이 고정값으로
 * 넣어야 했고, 넣지 않으면 전 행이 실패했다.
 */
class FormLoginConnectorIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private ConnectorRunner runner;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private ConnectorMappingRepository mappingRepository;
    @Autowired private ConnectorFieldMapRepository fieldMapRepository;
    @Autowired private TargetModelRepository targetModelRepository;
    @Autowired private ConnectorSecretService secretService;
    @Autowired private JdbcClient jdbcClient;

    private HttpServer server;
    private Connector connector;

    @BeforeEach
    void setUp() throws IOException {
        TenantContext.set(TENANT);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // 로그인 화면. 숨은 토큰을 심어 둔다 — 상대가 이것을 요구하는 경우가 흔하다.
        server.createContext("/login.html", exchange -> {
            String body;
            if ("GET".equals(exchange.getRequestMethod())) {
                body = "<html><form>"
                        + "<input type=\"hidden\" name=\"token\" value=\"tok-123\">"
                        + "</form></html>";
            } else {
                // 로그인 성공 — 세션 쿠키를 준다. 이름에 SESS 가 들어가야 세션으로 본다.
                exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=abc123; Path=/");
                body = "<html>ok</html>";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        // 조회. 화면이 쓰는 경로라 응답이 제품마다 다르다 — rows 아래에 행이 온다.
        server.createContext("/function.html", exchange -> {
            String json = "{\"page\":1,\"total\":1,\"rows\":["
                    + "{\"item_cd\":\"W-227\",\"item_nm\":\"제품A 227g\",\"qty\":\"9000\"},"
                    + "{\"item_cd\":\"W-100\",\"item_nm\":\"제품B 100g\",\"qty\":\"250\"}]}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT, "std_stock_snapshot").orElseThrow();
        String code = "form-" + UUID.randomUUID().toString().substring(0, 8);
        connector = connectorRepository.save(Connector.of(
                TENANT, code, "물류 시스템", model.getId(), SourceType.HTTP_API, "EA"));

        String ref = ConnectorSecretService.refOf(code);
        secretService.put(ref, "password", "dummy-password");

        String local = "http://127.0.0.1:" + server.getAddress().getPort();
        connector.configureSource(Map.of(
                "preset", "CUSTOM_FORM",
                "loginUrl", local + "/login.html",
                "fetchUrl", local + "/function.html",
                "useridField", "userid",
                "passwordField", "passwd",
                "tokenField", "token",
                "rowsPath", "rows",
                "userId", "tester",
                "fetchBody", "template=I100&action=search"), ref);
        connectorRepository.save(connector);

        ConnectorMapping mapping = mappingRepository.save(ConnectorMapping.draft(
                TENANT, connector.getId(), 1,
                Map.of("fields", List.of("item_cd", "item_nm", "qty"))));

        // 사람이 고르는 것은 이 셋뿐이다. 출처·기준 시각은 시스템이 채운다 —
        // 예전에는 고정값으로 직접 넣어야 했고, 넣지 않으면 전 행이 실패했다.
        fieldMapRepository.saveAll(List.of(
                ConnectorFieldMap.of(TENANT, mapping.getId(), "item_cd", "item_ref", Map.of(), 1),
                ConnectorFieldMap.of(TENANT, mapping.getId(), "qty", "quantity", Map.of(), 2),
                ConnectorFieldMap.of(TENANT, mapping.getId(), "item_nm", "raw_item_name",
                        Map.of(), 3)));
        mapping.activate();
        mappingRepository.save(mapping);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        TenantContext.clear();
    }

    /**
     * 세 단계를 다 지나 자료가 실제로 담기는가.
     *
     * <p>«연결됐다» 는 말만으로는 부족하다 — 로그인은 됐는데 조회 권한이 없거나, 응답 모양이
     * 달라 행을 못 찾는 일이 흔하다. 마지막에 표에 몇 줄이 담겼는지까지 봐야 한다.
     */
    @Test
    @DisplayName("웹 로그인으로 붙는 원천도 끝까지 돈다")
    void 폼_로그인_원천이_돈다() {
        var run = runner.run(new RunRequest(
                connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, null, 0));

        assertThat(run.getStatus().name())
                .as("어느 단계에서 막혔는지는 실행 기록과 로그에 남는다")
                .isIn("SUCCEEDED", "PARTIAL");
        assertThat(run.getSuccessCount())
                .as("담긴 줄이 없으면 «연결됐다» 는 말이 의미가 없다")
                .isEqualTo(2);
    }

    /**
     * 출처와 기준 시각을 <b>사람이 넣지 않아도</b> 채워지는가.
     *
     * <p>이 둘은 상대가 보내주는 값이 아니라 우리가 아는 값이다. 화면에서 묻지 않기로 했으므로
     * 시스템이 채워야 하고, 채우지 않으면 필수 검사에 걸려 전 행이 실패한다.
     */
    @Test
    @DisplayName("출처와 기준 시각을 시스템이 채운다")
    void 시스템값이_채워진다() {
        runner.run(new RunRequest(connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, null, 0));

        var rows = jdbcClient.sql("""
                        SELECT source, base_at, raw_item_name
                        FROM std_stock_snapshot
                        WHERE tenant_id = :tenant AND source = :source
                        """)
                .param("tenant", TENANT)
                .param("source", connector.getCode())
                .query()
                .listOfRows();

        assertThat(rows)
                .as("출처는 이 연동 이름이다 — 대조 정의가 이 이름으로 원천을 가리킨다")
                .hasSize(2);
        assertThat(rows.get(0).get("base_at"))
                .as("기준 시각이 비면 대조가 «언제 것끼리» 비교하는지 알 수 없다")
                .isNotNull();
        assertThat(rows)
                .as("품명이 있어야 나중에 두 시스템의 같은 물건을 이을 수 있다")
                .anySatisfy(row ->
                        assertThat(String.valueOf(row.get("raw_item_name"))).contains("제품A"));
    }
}
