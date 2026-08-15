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
import java.util.stream.IntStream;
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
 * <b>고른 대로 중간 표에 담기는가.</b>
 *
 * <p>이 제품이 하는 일은 결국 하나다 — 여러 시스템의 재고를 <b>한 표</b>에 같은 모양으로 담고,
 * 그 표끼리 견주는 것. 담기지 않으면 그 뒤는 전부 의미가 없다.
 *
 * <p>그런데 실제로는 24행이 <b>한 줄도 담기지 않는</b> 일이 며칠 이어졌다. 화면에는 고른 칸이
 * 그대로 보였고, 연결 테스트도 통과했고, 실행은 「완료」라고 떴다. 원인은 매번 화면과 저장된
 * 것 사이의 틈에 있었는데, <b>그 틈을 지나는 테스트가 없었다.</b> 화면 테스트는 「열리는가」
 * 까지, 엔진 테스트는 값 객체까지만 봤다.
 *
 * <p>그래서 이 테스트는 <b>상대 서버 응답부터 표에 담긴 값까지</b> 한 번에 지난다. 응답 모양도
 * 실제로 받은 것을 그대로 쓴다 — 값이 한 겹 더 들어가 있고({@code cell}) 숫자에 HTML 이 섞여
 * 온다. 그 둘을 벗기지 못하면 여기서 걸린다.
 */
class GridSourceToStandardTableIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    /** 실제로 받은 품목 수. 「몇 건이 담겼나」는 사장님이 화면에서 가장 먼저 보는 숫자다. */
    private static final int ITEM_COUNT = 24;

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
        server.createContext("/login.html", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Set-Cookie", "PHPSESSID=abc; Path=/");
            }
            respond(exchange, "<html><form></form></html>");
        });
        server.createContext("/function.html", exchange -> respond(exchange, gridResponse()));
        server.start();

        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT, "std_stock_snapshot").orElseThrow();
        String code = "grid-" + UUID.randomUUID().toString().substring(0, 8);
        connector = connectorRepository.save(Connector.of(
                TENANT, code, "물류 시스템", model.getId(), SourceType.HTTP_API, "EA"));

        String ref = ConnectorSecretService.refOf(code);
        secretService.put(ref, "password", "dummy");

        String local = "http://127.0.0.1:" + server.getAddress().getPort();
        connector.configureSource(Map.of(
                "preset", "CUSTOM_FORM",
                "loginUrl", local + "/login.html",
                "fetchUrl", local + "/function.html",
                "rowsPath", "rows",
                "userId", "tester",
                "fetchBody", "template=I100&action=search"), ref);
        connectorRepository.save(connector);

        // 사장님이 화면에서 실제로 고른 그대로. 시스템이 채우는 출처·기준 시각은 고르지 않는다.
        ConnectorMapping mapping = mappingRepository.save(ConnectorMapping.draft(
                TENANT, connector.getId(), 1, Map.of("fields", List.of("key"))));
        fieldMapRepository.saveAll(List.of(
                pick(mapping.getId(), "key", "item_ref", 1),
                pick(mapping.getId(), "stock_normal", "quantity", 2),
                pick(mapping.getId(), "product_name", "raw_item_name", 3),
                pick(mapping.getId(), "barcode", "product_key", 4),
                pick(mapping.getId(), "stock_info_st_1_wh_1", "defective_quantity", 5)));
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

    /** 화면에서 「이 칸을 쓴다」 고 고른 줄. 변환 규칙은 걸지 않는다 — 값을 그대로 읽는다. */
    private static ConnectorFieldMap pick(UUID mappingId, String source, String target, int order) {
        return ConnectorFieldMap.of(TENANT, mappingId, source, target, Map.of(), order);
    }

    /**
     * 상대가 실제로 주는 모양.
     *
     * <p>값이 {@code cell} 안에 한 겹 더 들어 있고, 같은 수량이 순수 숫자로도 오고 링크가 걸린
     * 채로도 온다. 8번 품목의 {@code product_id} 에는 화면 배지 글자가 섞여 있다 — 그것을
     * 품목 코드로 쓰면 그 품목만 영영 안 맞는다.
     */
    private static String gridResponse() {
        String rows = IntStream.rangeClosed(1, ITEM_COUNT)
                .mapToObj(index -> """
                        {"id":%d,"cell":{\
                        "product_id":"<a href=javascript:view('%05d')>%05d%s</a>",\
                        "key":"%05d",\
                        "product_name":"제품%d 227g (27.0%d.1%d)",\
                        "barcode":"CY99%05d",\
                        "stock_normal":"%d",\
                        "stock_info_st_0_wh_1":"<a class='clickable'><span>%d</span></a>",\
                        "stock_info_st_1_wh_1":"<a class='clickable'><span>0</span></a>",\
                        "location":"<input type=text><button>로그</button>",\
                        "options":""}}"""
                        .formatted(index - 1, index, index, index == 8 ? "품절" : "", index,
                                index, index % 9 + 1, index % 9, index,
                                index * 100, index * 100))
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return "{\"rows\":[" + rows + "],\"records\":" + ITEM_COUNT + ",\"page\":\"1\"}";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * 24행이 <b>한 줄도 빠짐없이</b> 담기는가.
     *
     * <p>「거의 담겼다」 는 없다. 한 줄이 빠지면 그 품목만 대조에서 조용히 사라지고, 그 사실은
     * 아무 데도 드러나지 않는다.
     */
    @Test
    @DisplayName("고른 대로 24행이 중간 표에 담긴다")
    void 고른_대로_담긴다() {
        var run = runner.run(new RunRequest(
                connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, null, 0));

        assertThat(run.getFailedCount())
                .as("담지 못한 줄이 있으면 그 품목은 대조에서 조용히 사라진다")
                .isZero();
        assertThat(run.getSuccessCount()).isEqualTo(ITEM_COUNT);

        var rows = jdbcClient.sql("""
                        SELECT item_ref, quantity, raw_item_name, product_key,
                               defective_quantity, source, base_at
                        FROM std_stock_snapshot
                        WHERE tenant_id = :tenant AND source = :source
                        ORDER BY item_ref
                        """)
                .param("tenant", TENANT)
                .param("source", connector.getCode())
                .query()
                .listOfRows();

        assertThat(rows).hasSize(ITEM_COUNT);

        var first = rows.getFirst();
        assertThat(first.get("item_ref"))
                .as("품목 코드는 순수한 칸에서 와야 한다 — 링크가 걸린 칸은 배지 글자가 섞인다")
                .isEqualTo("00001");
        assertThat(String.valueOf(first.get("quantity")))
                .as("수량이 안 담기면 대조할 것이 없다")
                .startsWith("100");
        assertThat(String.valueOf(first.get("raw_item_name")))
                .as("품명이 없으면 두 시스템의 같은 물건을 이을 수 없다")
                .contains("제품1");
        assertThat(first.get("product_key")).isEqualTo("CY9900001");
        assertThat(first.get("source"))
                .as("출처·기준 시각은 고르지 않아도 시스템이 채운다")
                .isEqualTo(connector.getCode());
        assertThat(first.get("base_at")).isNotNull();
    }

    /**
     * 배지 글자가 섞인 칸을 고르지 않았으므로 <b>그 품목도 멀쩡해야</b> 한다.
     *
     * <p>8번 품목은 상대가 {@code 00008품절} 처럼 보내는 칸을 갖고 있다. 그 칸을 골랐다면 이
     * 품목만 코드가 달라져 대조에서 영영 안 맞는데, 그 사실은 <b>어디에도 오류로 남지 않는다.</b>
     */
    @Test
    @DisplayName("배지 글자가 섞인 칸을 쓰지 않으면 그 품목도 멀쩡히 담긴다")
    void 오염된_칸을_피하면_멀쩡하다() {
        runner.run(new RunRequest(connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, null, 0));

        var codes = jdbcClient.sql("""
                        SELECT item_ref FROM std_stock_snapshot
                        WHERE tenant_id = :tenant AND source = :source
                        """)
                .param("tenant", TENANT)
                .param("source", connector.getCode())
                .query(String.class)
                .list();

        assertThat(codes)
                .as("품목 코드에 화면 글자가 섞이면 그 품목만 조용히 안 맞는다")
                .contains("00008")
                .noneMatch(code -> code.contains("품절"));
    }

    /**
     * 고르지 않은 칸도 <b>버려지지 않는가</b>.
     *
     * <p>화면이 「보관됩니다」 라고 말해 두고 실제로 버리면, 나중에 필요해졌을 때 되돌릴 방법이
     * 없다. 그때는 이미 원천의 그 시점 자료를 다시 볼 수 없다.
     */
    @Test
    @DisplayName("연결하지 않은 칸도 함께 보관된다")
    void 고르지_않은_칸도_남는다() {
        runner.run(new RunRequest(connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, null, 0));

        var attributes = jdbcClient.sql("""
                        SELECT attributes::text FROM std_stock_snapshot
                        WHERE tenant_id = :tenant AND source = :source LIMIT 1
                        """)
                .param("tenant", TENANT)
                .param("source", connector.getCode())
                .query(String.class)
                .single();

        assertThat(attributes)
                .as("화면이 「보관됩니다」 라고 한 것을 지키지 않으면 그 말이 거짓이 된다")
                .contains("stock_info_st_0_wh_1");
    }
}
