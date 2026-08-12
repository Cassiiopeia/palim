package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
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
import kr.suhsaechan.palim.connector.run.RollbackService;
import kr.suhsaechan.palim.connector.run.RunMode;
import kr.suhsaechan.palim.connector.run.RunRequest;
import kr.suhsaechan.palim.connector.run.RunStatus;
import kr.suhsaechan.palim.connector.run.RunTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 파이프라인 E2E.
 *
 * <p>설계가 핵심이라고 지목한 것들을 끝에서 끝까지 확인한다 — <b>TEST 가 운영을 건드리지
 * 않는가</b>, <b>재실행에 중복이 생기지 않는가</b>, <b>되돌리기가 정확히 그만큼만 되돌리는가</b>.
 * 여기가 깨지면 사람이 시스템을 믿지 못하고, 믿지 못하면 쓰지 않는다.
 */
class ConnectorPipelineIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @TempDir Path tempDir;

    @Autowired private ConnectorRunner runner;
    @Autowired private RollbackService rollbackService;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private ConnectorMappingRepository mappingRepository;
    @Autowired private ConnectorFieldMapRepository fieldMapRepository;
    @Autowired private TargetModelRepository targetModelRepository;
    @Autowired private TargetFieldRepository targetFieldRepository;
    @Autowired private JdbcClient jdbcClient;

    private TargetModel model;
    private String source;

    @BeforeEach
    void setUp() {
        model = targetModelRepository.findByTenantIdAndCode(TENANT, "std_stock_snapshot")
                .orElseThrow();
        registerFields();
        // 테스트마다 다른 source 를 써서 자연키가 겹치지 않게 한다.
        source = "SRC-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("업로드부터 적재까지 전 과정이 돈다")
    void 전_과정이_동작한다() throws IOException {
        Connector connector = activeConnector();
        Path csv = write("""
                품목코드,수량,기준일
                A-001,10,2026-08-12
                A-002,20,2026-08-12
                """);

        ConnectorRun run = runner.run(RunRequest.upload(
                connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, csv));

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getSuccessCount()).isEqualTo(2);
        assertThat(countSnapshot()).isEqualTo(2);
    }

    @Test
    @DisplayName("TEST 실행은 스테이징에만 쌓이고 운영 테이블은 그대로다")
    void 테스트_실행은_운영을_건드리지_않는다() throws IOException {
        Connector connector = activeConnector();
        Path csv = write("품목코드,수량,기준일\nA-001,10,2026-08-12\n");

        ConnectorRun run = runner.run(RunRequest.upload(
                connector.getId(), RunMode.TEST, RunTrigger.MANUAL, csv));

        assertThat(run.getSuccessCount()).isEqualTo(1);
        assertThat(countStaging(run.getId())).isEqualTo(1);
        assertThat(countSnapshot()).as("TEST 가 운영에 닿으면 대사 결과가 오염된다").isZero();
    }

    @Test
    @DisplayName("일부 행이 깨져도 나머지는 적재된다")
    void 부분_실패를_허용한다() throws IOException {
        Connector connector = activeConnector();
        Path csv = write("""
                품목코드,수량,기준일
                A-001,10,2026-08-12
                A-002,없음,2026-08-12
                A-003,30,2026-08-12
                """);

        ConnectorRun run = runner.run(RunRequest.upload(
                connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, csv));

        assertThat(run.getStatus()).isEqualTo(RunStatus.PARTIAL);
        assertThat(run.getSuccessCount()).isEqualTo(2);
        assertThat(run.getFailedCount()).isEqualTo(1);
        assertThat(countSnapshot()).as("한 행 때문에 나머지를 버리면 아무도 쓰지 않는다").isEqualTo(2);
    }

    @Test
    @DisplayName("실패 행은 원본째 보존되어 사람이 고칠 수 있다")
    void 실패_행을_보존한다() throws IOException {
        Connector connector = activeConnector();
        Path csv = write("품목코드,수량,기준일\nA-001,없음,2026-08-12\n");

        ConnectorRun run = runner.run(RunRequest.upload(
                connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, csv));

        String sourceRow = jdbcClient.sql("""
                        SELECT source_row::text FROM connector_run_error WHERE run_id = :id
                        """)
                .param("id", run.getId()).query(String.class).single();
        assertThat(sourceRow).contains("A-001", "없음");
    }

    @Test
    @DisplayName("같은 파일을 두 번 넣어도 행이 늘지 않는다")
    void 재실행이_중복을_만들지_않는다() throws IOException {
        Connector connector = activeConnector();
        Path csv = write("품목코드,수량,기준일\nA-001,10,2026-08-12\n");

        runner.run(RunRequest.upload(connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, csv));
        runner.run(RunRequest.upload(connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, csv));

        assertThat(countSnapshot()).as("재시도가 안전해야 자동화를 켠다").isEqualTo(1);
    }

    @Test
    @DisplayName("DRAFT 매핑으로 TEST 는 되지만 LIVE 는 막힌다")
    void DRAFT_는_테스트만_가능하다() throws IOException {
        Connector connector = draftConnector();
        Path csv = write("품목코드,수량,기준일\nA-001,10,2026-08-12\n");

        ConnectorRun testRun = runner.run(RunRequest.upload(
                connector.getId(), RunMode.TEST, RunTrigger.MANUAL, csv));
        assertThat(testRun.getStatus()).isEqualTo(RunStatus.SUCCEEDED);

        assertThatThrownBy(() -> runner.run(RunRequest.upload(
                connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, csv)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MAPPING_NOT_ACTIVE);
    }

    @Test
    @DisplayName("TEST 되돌리기는 스테이징만 비운다")
    void 테스트를_되돌린다() throws IOException {
        Connector connector = activeConnector();
        Path csv = write("품목코드,수량,기준일\nA-001,10,2026-08-12\n");
        ConnectorRun run = runner.run(RunRequest.upload(
                connector.getId(), RunMode.TEST, RunTrigger.MANUAL, csv));

        rollbackService.rollback(run.getId());

        assertThat(countStaging(run.getId())).isZero();
    }

    @Test
    @DisplayName("되돌리면 이번 실행이 만든 행이 사라진다")
    void 되돌리기가_정확히_지운다() throws IOException {
        Connector connector = activeConnector();
        Path csv = write("품목코드,수량,기준일\nA-001,10,2026-08-12\n");
        ConnectorRun run = runner.run(RunRequest.upload(
                connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, csv));

        rollbackService.rollback(run.getId());

        assertThat(countSnapshot()).isZero();
    }

    @Test
    @DisplayName("덮어쓴 행은 이전 값으로 되돌아간다")
    void 덮어쓴_값을_복원한다() throws IOException {
        Connector connector = activeConnector();
        runner.run(RunRequest.upload(connector.getId(), RunMode.LIVE, RunTrigger.MANUAL,
                write("품목코드,수량,기준일\nA-001,10,2026-08-12\n")));

        ConnectorRun second = runner.run(RunRequest.upload(connector.getId(), RunMode.LIVE,
                RunTrigger.MANUAL, write("품목코드,수량,기준일\nA-001,99,2026-08-12\n")));
        assertThat(quantityOf("A-001")).isEqualByComparingTo("99");

        rollbackService.rollback(second.getId());

        assertThat(countSnapshot()).as("행이 사라지면 안 된다 — 이전 값이 있었다").isEqualTo(1);
        assertThat(quantityOf("A-001")).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("가장 최근 LIVE 실행이 아니면 되돌릴 수 없다")
    void 최근_실행만_되돌린다() throws IOException {
        Connector connector = activeConnector();
        Path csv = write("품목코드,수량,기준일\nA-001,10,2026-08-12\n");
        ConnectorRun first = runner.run(RunRequest.upload(
                connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, csv));
        runner.run(RunRequest.upload(connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, csv));

        assertThatThrownBy(() -> rollbackService.rollback(first.getId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROLLBACK_NOT_ALLOWED);
    }

    @Test
    @DisplayName("매핑에 쓰는 컬럼이 사라지면 적재하지 않고 중단한다")
    void 드리프트를_막는다() throws IOException {
        Connector connector = activeConnector();
        // 확정 스냅샷에는 '수량' 이 있는데 파일에서 사라졌다.
        Path csv = write("품목코드,기준일\nA-001,2026-08-12\n");

        ConnectorRun run = runner.run(RunRequest.upload(
                connector.getId(), RunMode.LIVE, RunTrigger.MANUAL, csv));

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(countSnapshot()).as("조용히 잘못된 데이터가 들어가는 것이 최악이다").isZero();
    }

    // ---------- 픽스처 ----------

    /** 표준 모델의 필드 정의. 없으면 변환 엔진이 아무것도 매핑하지 못한다. */
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

    private Connector activeConnector() {
        return connector(true);
    }

    private Connector draftConnector() {
        return connector(false);
    }

    private Connector connector(boolean activate) {
        Connector connector = connectorRepository.save(Connector.of(TENANT,
                "c-" + UUID.randomUUID(), "테스트 커넥터", model.getId(), SourceType.UPLOAD, "EA"));

        ConnectorMapping mapping = ConnectorMapping.draft(TENANT, connector.getId(), 1,
                Map.of("fields", List.of("품목코드", "수량", "기준일")));
        if (activate) {
            mapping.activate();
        }
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

    private Path write(String content) throws IOException {
        Path csv = tempDir.resolve("data-" + UUID.randomUUID() + ".csv");
        Files.writeString(csv, content, StandardCharsets.UTF_8);
        return csv;
    }

    private int countSnapshot() {
        return jdbcClient.sql(
                        "SELECT count(*)::int FROM std_stock_snapshot WHERE source = :src")
                .param("src", source).query(Integer.class).single();
    }

    private BigDecimal quantityOf(String itemRef) {
        return jdbcClient.sql("""
                        SELECT quantity FROM std_stock_snapshot
                        WHERE source = :src AND item_ref = :ref
                        """)
                .param("src", source).param("ref", itemRef).query(BigDecimal.class).single();
    }

    private int countStaging(UUID runId) {
        return jdbcClient.sql("SELECT count(*)::int FROM connector_staging WHERE run_id = :id")
                .param("id", runId).query(Integer.class).single();
    }
}
