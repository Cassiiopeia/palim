package kr.suhsaechan.palim.integration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import kr.suhsaechan.palim.connector.model.FieldDataType;
import kr.suhsaechan.palim.connector.model.TargetField;
import kr.suhsaechan.palim.connector.model.TargetFieldRepository;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.connector.run.ConnectorRun;
import kr.suhsaechan.palim.connector.run.ConnectorRunner;
import kr.suhsaechan.palim.connector.run.RunMode;
import kr.suhsaechan.palim.connector.run.RunRequest;
import kr.suhsaechan.palim.connector.run.RunTrigger;
import kr.suhsaechan.palim.web.connector.ConnectorAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 담은 것을 <b>화면에서 확인할 수 있는가</b>.
 *
 * <p>실제 적재는 결과 보관함을 거치지 않고 표준 모델 표에 바로 쓴다. 화면이 보관함만 보고
 * 있어서, 45 건이 멀쩡히 들어간 실행이 「보여줄 내역이 없습니다 — 실제 적재는 결과를 따로 담아
 * 두지 않습니다」 로 보였다. <b>사실이 아닌 말이었다.</b> 담아 두고 있었고 화면이 안 찾았을 뿐이다.
 *
 * <p>확인할 수 없으면 확인 단계가 형식이 된다. 그러면 잘못 담긴 값이 그대로 통과하고, 그 뒤
 * 대조가 전부 그 값을 기준으로 돈다.
 */
@AutoConfigureMockMvc
class RunDetailScreenIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = ConnectorAdminService.DEFAULT_TENANT;

    @TempDir Path tempDir;

    @Autowired private MockMvc mockMvc;
    @Autowired private ConnectorRunner runner;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private ConnectorMappingRepository mappingRepository;
    @Autowired private ConnectorFieldMapRepository fieldMapRepository;
    @Autowired private TargetModelRepository targetModelRepository;
    @Autowired private TargetFieldRepository targetFieldRepository;

    private TargetModel model;
    private String source;

    @BeforeEach
    void setUp() {
        model = targetModelRepository.findByTenantIdAndCode(TENANT, "std_stock_snapshot")
                .orElseThrow();
        registerFields();
        // 테스트마다 다른 원천 이름을 써서 자연키가 겹치지 않게 한다.
        source = "SRC-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 이 시험이 지키는 것.
     *
     * <p>담긴 값이 화면에 있어야 하고, 「담아 두지 않는다」 는 말이 없어야 한다. 값이 보이는 것만
     * 확인하면 옛 문구가 그 아래 남아 있어도 통과한다 — 화면이 두 가지를 동시에 말하게 된다.
     */
    @Test
    @WithMockUser
    @DisplayName("실제 적재도 담긴 줄을 보여준다")
    void 실제_적재도_담긴_줄을_보여준다() throws Exception {
        Connector connector = connector();
        ConnectorRun run = load(connector, """
                품목코드,수량,기준일
                A-001,10,2026-08-12
                A-002,20,2026-08-12
                """);

        mockMvc.perform(get("/connectors/{id}/runs/{runId}", connector.getId(), run.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("담긴 모습")))
                .andExpect(content().string(containsString("A-001")))
                .andExpect(content().string(containsString("A-002")))
                // 어디에 담겼는지 밝혀야 확인하러 갈 곳을 안다
                .andExpect(content().string(containsString("std_stock_snapshot")))
                .andExpect(content().string(not(containsString("결과를 따로 담아 두지 않습니다"))))
                .andExpect(RenderAssertions.fullyRendered());
    }

    /**
     * 담은 건수와 지금 남은 건수가 다를 수 있다.
     *
     * <p>표준 모델은 자리마다 「마지막으로 담은 실행」 하나만 기억한다. 같은 자리를 다시 담으면
     * 주인이 새 실행으로 넘어간다. 화면이 이 사정을 말하지 않으면 「2건 성공」 바로 아래에 빈 표가
     * 놓여, 사람은 적재가 깨진 줄 안다.
     */
    @Test
    @WithMockUser
    @DisplayName("이후 실행이 덮어쓴 줄은 그 사정을 말한다")
    void 덮어쓴_줄은_사정을_말한다() throws Exception {
        Connector connector = connector();
        String rows = """
                품목코드,수량,기준일
                A-001,10,2026-08-12
                """;
        ConnectorRun first = load(connector, rows);
        load(connector, rows);

        mockMvc.perform(get("/connectors/{id}/runs/{runId}", connector.getId(), first.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("이후 실행이")))
                .andExpect(content().string(not(containsString("결과를 따로 담아 두지 않습니다"))))
                .andExpect(RenderAssertions.fullyRendered());
    }

    /**
     * 「무엇이 담겼나」 만으로는 그 값이 맞는지 판단할 수 없다.
     *
     * <p>어느 칸을 어디에 넣기로 하고 담은 것인지 알아야 틀린 자리를 짚을 수 있다. 값만 보고
     * 이상하다 싶으면 사람은 연결 화면을 따로 열어 대조해야 하고, 그 사이 어느 실행 이야기였는지를
     * 잃는다.
     */
    @Test
    @WithMockUser
    @DisplayName("그때 쓰던 칸 연결을 함께 보여준다")
    void 그때_쓰던_연결을_보여준다() throws Exception {
        Connector connector = connector();
        ConnectorRun run = load(connector, """
                품목코드,수량,기준일
                A-001,10,2026-08-12
                """);

        mockMvc.perform(get("/connectors/{id}/runs/{runId}", connector.getId(), run.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("이 실행의 설정")))
                .andExpect(content().string(containsString("품목코드")))
                .andExpect(content().string(containsString("item_ref")))
                .andExpect(RenderAssertions.fullyRendered());
    }

    /**
     * 연결에 없는 칸도 값이 담기면 보여준다.
     *
     * <p>단위 환산 결과({@code base_quantity})는 연결 없이 채워진다. 연결한 칸만 그리면 이 값이
     * 화면에서 통째로 사라지는데, <b>대조가 더하는 것이 바로 그 칸</b>이다. 「10 EA 를 넣었는데
     * 무엇으로 환산돼 담겼나」 를 볼 수 없으면, 대조 결과가 틀렸을 때 어디를 봐야 할지 알 수 없다.
     */
    @Test
    @WithMockUser
    @DisplayName("연결하지 않았어도 담긴 값은 보여준다")
    void 연결하지_않은_칸도_담겼으면_보여준다() throws Exception {
        Connector connector = connector();
        ConnectorRun run = load(connector, """
                품목코드,수량,기준일
                A-001,10,2026-08-12
                """);

        mockMvc.perform(get("/connectors/{id}/runs/{runId}", connector.getId(), run.getId()))
                .andExpect(status().isOk())
                // 연결한 칸은 넷뿐인데(품목코드·수량·기준일·원천) 환산 결과가 함께 담긴다
                .andExpect(content().string(containsString("base_quantity")))
                .andExpect(RenderAssertions.fullyRendered());
    }

    /** 시험 실행은 예전 그대로여야 한다. 실제를 고치다 시험을 잃으면 확인 단계가 사라진다. */
    @Test
    @WithMockUser
    @DisplayName("시험 실행은 담길 모습을 그대로 보여준다")
    void 시험_실행은_그대로다() throws Exception {
        Connector connector = connector();
        ConnectorRun run = runner.run(RunRequest.upload(connector.getId(), RunMode.TEST,
                RunTrigger.MANUAL, write("""
                        품목코드,수량,기준일
                        A-001,10,2026-08-12
                        """)));

        mockMvc.perform(get("/connectors/{id}/runs/{runId}", connector.getId(), run.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("담길 모습")))
                .andExpect(content().string(containsString("A-001")))
                .andExpect(content().string(
                        containsString("진짜 자료에는 아직 아무것도 들어가지 않았습니다")))
                .andExpect(RenderAssertions.fullyRendered());
    }

    private ConnectorRun load(Connector connector, String csv) throws IOException {
        return runner.run(RunRequest.upload(connector.getId(), RunMode.LIVE, RunTrigger.MANUAL,
                write(csv)));
    }

    private Connector connector() {
        Connector connector = connectorRepository.save(Connector.of(TENANT,
                "run-" + UUID.randomUUID(), "적재 확인용", model.getId(), SourceType.UPLOAD, "EA"));

        ConnectorMapping mapping = ConnectorMapping.draft(TENANT, connector.getId(), 1,
                Map.of("fields", List.of("품목코드", "수량", "기준일")));
        mapping.activate();
        mappingRepository.save(mapping);

        // source 는 원천에 없는 값이라 기본값 규칙으로 채운다.
        fieldMapRepository.saveAll(List.of(
                ConnectorFieldMap.of(TENANT, mapping.getId(), "품목코드", "item_ref", Map.of(), 1),
                ConnectorFieldMap.of(TENANT, mapping.getId(), "수량", "quantity", Map.of(), 2),
                ConnectorFieldMap.of(TENANT, mapping.getId(), "기준일", "base_at", Map.of(), 3),
                ConnectorFieldMap.of(TENANT, mapping.getId(), "__source__", "source",
                        Map.of("type", "DEFAULT_IF_EMPTY", "params", Map.of("value", source)), 4)));
        return connector;
    }

    private void registerFields() {
        register("item_ref", FieldDataType.STRING, true, 1);
        register("source", FieldDataType.STRING, true, 2);
        register("base_at", FieldDataType.TIMESTAMP, true, 3);
        register("quantity", FieldDataType.DECIMAL, true, 4);
        register("base_quantity", FieldDataType.DECIMAL, false, 5);
        register("base_unit", FieldDataType.STRING, false, 6);
        register("unit", FieldDataType.STRING, false, 7);
    }

    private void register(String key, FieldDataType type, boolean required, int order) {
        if (targetFieldRepository.existsByTargetModelIdAndFieldKey(model.getId(), key)) {
            return;
        }
        targetFieldRepository.save(TargetField.of(TENANT, model.getId(), key, key, type,
                required, null, order));
    }

    private Path write(String content) throws IOException {
        Path csv = tempDir.resolve("data-" + UUID.randomUUID() + ".csv");
        Files.writeString(csv, content, StandardCharsets.UTF_8);
        return csv;
    }
}
