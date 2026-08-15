package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
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
import kr.suhsaechan.palim.connector.run.ConnectorRun;
import kr.suhsaechan.palim.connector.run.ConnectorRunner;
import kr.suhsaechan.palim.connector.run.RunMode;
import kr.suhsaechan.palim.connector.run.RunRequest;
import kr.suhsaechan.palim.connector.run.RunTrigger;
import kr.suhsaechan.palim.connector.script.PostScript;
import kr.suhsaechan.palim.connector.script.PostScriptRepository;
import kr.suhsaechan.palim.connector.script.PostScriptRunRepository;
import kr.suhsaechan.palim.connector.secret.ConnectorSecretService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 사장님이 쓴 스크립트가 <b>원천에서 표까지 실제로 돌아가는가</b>.
 *
 * <p>흉내 낸 것으로 확인하면 「우리 코드끼리는 말이 맞는다」까지만 알 수 있다. 진짜 파이썬을
 * 돌려 진짜 표에 담기는 것까지 봐야, 프로세스·인코딩·JSON 왕복 어디에서도 안 깨진다는 것을
 * 안다.
 *
 * <p>이 기능이 필요한 이유가 여기 그대로 재현돼 있다 — 두 시스템의 품명 표기가 달라
 * (「초콜릿」 vs 「초콜렛」) 같은 물건인데 이어지지 않는다.
 */
class PostScriptPipelineIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    /** 설계 문서와 화면 예제에 적은 것과 같은 글. 이것이 안 돌면 문서가 거짓이 된다. */
    private static final String NORMALIZE = """
            import sys, json, re

            SAME = {"초콜렛": "초콜릿"}

            rows = json.load(sys.stdin)["rows"]
            out = []
            for r in rows:
                name = r["raw_item_name"] or ""
                for word, to in SAME.items():
                    name = name.replace(word, to)
                name = re.sub(r'\\([^)]*\\)', '', name)
                out.append({"_row": r["_row"],
                            "normalized_name": re.sub(r'\\s+', '', name).lower()})

            print(json.dumps({"rows": out}, ensure_ascii=False))
            """;

    @Autowired private ConnectorRunner runner;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private ConnectorMappingRepository mappingRepository;
    @Autowired private ConnectorFieldMapRepository fieldMapRepository;
    @Autowired private TargetModelRepository targetModelRepository;
    @Autowired private ConnectorSecretService secretService;
    @Autowired private PostScriptRepository scriptRepository;
    @Autowired private PostScriptRunRepository scriptRunRepository;
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
            respond(exchange, "<html></html>");
        });
        server.createContext("/function.html", exchange -> respond(exchange, """
                {"rows":[
                  {"cell":{"key":"01013","product_name":"초콜렛 프로틴바","qty":"1571"}},
                  {"cell":{"key":"01014","product_name":"클래식 16g (26.11.07)","qty":"9451"}}
                ]}"""));
        server.start();

        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(TENANT, "std_stock_snapshot").orElseThrow();
        String code = "post-" + UUID.randomUUID().toString().substring(0, 8);
        connector = connectorRepository.save(Connector.of(
                TENANT, code, "물류", model.getId(), SourceType.HTTP_API, "EA"));
        secretService.put(ConnectorSecretService.refOf(code), "password", "dummy");

        String local = "http://127.0.0.1:" + server.getAddress().getPort();
        connector.configureSource(Map.of(
                "preset", "CUSTOM_FORM",
                "loginUrl", local + "/login.html",
                "fetchUrl", local + "/function.html",
                "rowsPath", "rows",
                "userId", "tester",
                "fetchBody", "x=1"), ConnectorSecretService.refOf(code));
        connectorRepository.save(connector);

        ConnectorMapping mapping = mappingRepository.save(ConnectorMapping.draft(
                TENANT, connector.getId(), 1, Map.of("fields", List.of("key"))));
        fieldMapRepository.saveAll(List.of(
                ConnectorFieldMap.of(TENANT, mapping.getId(), "key", "item_ref", Map.of(), 1),
                ConnectorFieldMap.of(TENANT, mapping.getId(), "product_name", "raw_item_name",
                        Map.of(), 2),
                // 수량은 필수다. 빠지면 담기 직전 검사에서 전 줄이 떨어진다.
                ConnectorFieldMap.of(TENANT, mapping.getId(), "qty", "quantity", Map.of(), 3)));
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

    private PostScript activeScript(String body) {
        PostScript script = PostScript.draft(TENANT, connector.getId(), "이름 다듬기", body, 1, 1);
        script.activate();
        return scriptRepository.save(script);
    }

    @Test
    @DisplayName("스크립트가 다듬은 이름이 표에 담긴다")
    void 다듬은_이름이_담긴다() {
        activeScript(NORMALIZE);

        runner.run(new RunRequest(connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, null, 0));

        var names = jdbcClient.sql("""
                        SELECT normalized_name FROM std_stock_snapshot
                        WHERE tenant_id = :tenant AND source = :source ORDER BY item_ref
                        """)
                .param("tenant", TENANT).param("source", connector.getCode())
                .query(String.class).list();

        assertThat(names)
                .as("표기가 달라 안 묶이던 것이 묶여야 이 기능의 의미가 있다")
                .containsExactly("초콜릿프로틴바", "클래식16g");
    }

    /** 돌려주지 않은 칸은 그대로여야 한다. 아니면 이름만 다듬는 스크립트가 나머지를 날린다. */
    @Test
    @DisplayName("스크립트가 돌려주지 않은 칸은 그대로 남는다")
    void 안_돌려준_칸은_그대로다() {
        activeScript(NORMALIZE);

        runner.run(new RunRequest(connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, null, 0));

        var rows = jdbcClient.sql("""
                        SELECT item_ref, raw_item_name FROM std_stock_snapshot
                        WHERE tenant_id = :tenant AND source = :source ORDER BY item_ref
                        """)
                .param("tenant", TENANT).param("source", connector.getCode())
                .query().listOfRows();

        assertThat(rows.get(0))
                .as("원본 품명이 사라지면 규칙을 고쳐도 되돌릴 수 없다")
                .containsEntry("item_ref", "01013")
                .containsEntry("raw_item_name", "초콜렛 프로틴바");
    }

    /**
     * 스크립트를 <b>건너뛰고</b> 돌릴 수 있는가.
     *
     * <p>스크립트를 거치기 전 모습과 대볼 수 있어야 「이게 스크립트 때문인지」를 가른다.
     */
    @Test
    @DisplayName("스크립트 없이 돌리면 원본 그대로 담긴다")
    void 스크립트_없이_돌린다() {
        activeScript(NORMALIZE);

        runner.run(new RunRequest(connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, null, 0,
                true));

        var names = jdbcClient.sql("""
                        SELECT normalized_name FROM std_stock_snapshot
                        WHERE tenant_id = :tenant AND source = :source
                        """)
                .param("tenant", TENANT).param("source", connector.getCode())
                .query(String.class).list();

        assertThat(names).as("건너뛰었으니 다듬어지지 않아야 한다").containsOnlyNulls();
    }

    /**
     * 스크립트가 필수 칸을 지우면.
     *
     * <p>모든 칸을 열어 두기로 했으므로 실제로 생긴다. <b>막지 않고 담기 직전에 잡아</b>
     * 그 줄만 떨어뜨린다 — 진짜 표는 멀쩡해야 한다.
     */
    @Test
    @DisplayName("스크립트가 품목코드를 지우면 그 줄만 떨어진다")
    void 필수칸을_지우면_그_줄만_떨어진다() {
        activeScript("""
                import sys, json
                rows = json.load(sys.stdin)["rows"]
                out = [{"_row": r["_row"], "normalized_name": "ok"} for r in rows]
                # 첫 줄의 품목코드를 지운다 — 손잡이가 따로 있어 이것도 바꿀 수 있다
                out[0]["item_ref"] = ""
                print(json.dumps({"rows": out}, ensure_ascii=False))
                """);

        ConnectorRun run = runner.run(
                new RunRequest(connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, null, 0));

        assertThat(run.getFailedCount())
                .as("지워진 줄은 담기지 않고 이유가 남아야 한다")
                .isEqualTo(1);
        assertThat(run.getSuccessCount())
                .as("한 줄 때문에 전체를 버리지 않는다")
                .isEqualTo(1);
    }

    /** 스크립트가 죽으면 실행 전체를 세운다. 반쯤 다듬어진 자료가 남는 것이 가장 나쁘다. */
    @Test
    @DisplayName("스크립트가 죽으면 실행이 실패로 끝나고 사유가 남는다")
    void 스크립트가_죽으면_실행이_선다() {
        activeScript("이건 파이썬이 아니다");

        ConnectorRun run = runner.run(
                new RunRequest(connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, null, 0));

        assertThat(run.getStatus().name()).isEqualTo("FAILED");
        assertThat(scriptRunRepository.findAll())
                .as("몇 건 바뀌었는지가 남아야 «조용히 아무것도 안 하게 된 것» 을 알아챈다")
                .isNotEmpty();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
