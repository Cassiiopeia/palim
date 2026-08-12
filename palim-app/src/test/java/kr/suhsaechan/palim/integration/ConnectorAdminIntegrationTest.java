package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.connector.run.ConnectorRun;
import kr.suhsaechan.palim.connector.run.ConnectorRunner;
import kr.suhsaechan.palim.connector.run.RunMode;
import kr.suhsaechan.palim.connector.run.RunRequest;
import kr.suhsaechan.palim.connector.run.RunStatus;
import kr.suhsaechan.palim.connector.run.RunTrigger;
import kr.suhsaechan.palim.connector.source.SourceSchema;
import kr.suhsaechan.palim.web.connector.ConnectorAdminService;
import kr.suhsaechan.palim.web.connector.ConnectorQueryService;
import kr.suhsaechan.palim.web.connector.ConnectorSummary;
import kr.suhsaechan.palim.web.connector.FieldMappingForm;
import kr.suhsaechan.palim.web.connector.RunSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 화면이 하는 일 전 과정.
 *
 * <p>컨트롤러는 얇은 위임이라 여기서 서비스 흐름을 검증한다 — 연동 만들기 → 파일에서 컬럼 읽기
 * → 매핑 저장 → 테스트 실행 → 확정 → 실제 적재.
 *
 * <p><b>AI 를 한 번도 부르지 않는다.</b> 수동만으로 완주되는지가 이 테스트의 목적이기도 하다.
 */
class ConnectorAdminIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = ConnectorAdminService.DEFAULT_TENANT;

    @TempDir Path tempDir;

    @Autowired private ConnectorAdminService adminService;
    @Autowired private ConnectorQueryService queryService;
    @Autowired private ConnectorRunner runner;
    @Autowired private TargetModelRepository targetModelRepository;

    @Test
    @DisplayName("연동 만들기부터 실제 적재까지 AI 없이 완주된다")
    void 전_과정이_수동으로_완주된다() throws IOException {
        Connector connector = newConnector();
        Path csv = write("""
                품목코드,수량,기준일
                A-001,10,2026-08-12
                A-002,20,2026-08-12
                """);

        // 1. 파일에서 컬럼 읽기 — 헤더만 보고 필드 목록을 뽑는다
        SourceSchema schema = adminService.readSchema(connector, csv, 1);
        assertThat(schema.fields()).containsExactly("품목코드", "수량", "기준일");
        assertThat(schema.totalCount()).isEqualTo(2);

        // 2. 사람이 좌우를 연결한다
        adminService.saveDraft(connector.getId(), schema, mappingForms());

        // 3. 테스트 실행 — 운영 테이블에 닿지 않는다
        ConnectorRun testRun = runner.run(new RunRequest(connector.getId(), RunMode.TEST,
                RunTrigger.MANUAL, csv, 1));
        assertThat(testRun.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(queryService.staging(testRun.getId(), 10)).hasSize(2);

        // 4. 확정 후에야 실제 적재가 된다
        adminService.activate(connector.getId());
        ConnectorRun liveRun = runner.run(new RunRequest(connector.getId(), RunMode.LIVE,
                RunTrigger.MANUAL, csv, 1));

        assertThat(liveRun.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(liveRun.getSuccessCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("확정 전에는 실제 적재가 막힌다")
    void 확정_전에는_적재할_수_없다() throws IOException {
        Connector connector = newConnector();
        Path csv = write("품목코드,수량,기준일\nA-001,10,2026-08-12\n");
        adminService.saveDraft(connector.getId(), adminService.readSchema(connector, csv, 1),
                mappingForms());

        assertThatThrownBy(() -> runner.run(new RunRequest(connector.getId(), RunMode.LIVE,
                RunTrigger.MANUAL, csv, 1)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MAPPING_NOT_ACTIVE);
    }

    @Test
    @DisplayName("매핑을 다시 저장해도 버전이 늘지 않는다 — 버전은 확정 단위다")
    void 초안_저장은_버전을_늘리지_않는다() throws IOException {
        Connector connector = newConnector();
        Path csv = write("품목코드,수량,기준일\nA-001,10,2026-08-12\n");
        SourceSchema schema = adminService.readSchema(connector, csv, 1);

        int first = adminService.saveDraft(connector.getId(), schema, mappingForms()).getVersion();
        int second = adminService.saveDraft(connector.getId(), schema, mappingForms()).getVersion();

        assertThat(second)
                .as("고칠 때마다 버전이 늘면 이력이 의미 없는 숫자로 가득 찬다")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("확정 후 다시 편집하면 새 버전이 생긴다")
    void 확정_후_편집은_새_버전이다() throws IOException {
        Connector connector = newConnector();
        Path csv = write("품목코드,수량,기준일\nA-001,10,2026-08-12\n");
        SourceSchema schema = adminService.readSchema(connector, csv, 1);
        adminService.saveDraft(connector.getId(), schema, mappingForms());
        adminService.activate(connector.getId());

        int next = adminService.saveDraft(connector.getId(), schema, mappingForms()).getVersion();

        assertThat(next).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 코드로 두 번 만들 수 없다")
    void 코드가_중복되면_거부한다() {
        String code = "dup-" + UUID.randomUUID();
        adminService.create(code, "첫 번째", snapshotModel().getId(), SourceType.UPLOAD, "EA");

        assertThatThrownBy(() -> adminService.create(code, "두 번째", snapshotModel().getId(),
                SourceType.UPLOAD, "EA"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("목록이 확정 여부와 마지막 실행 결과를 함께 보여준다")
    void 목록에_상태가_담긴다() throws IOException {
        Connector connector = newConnector();
        Path csv = write("품목코드,수량,기준일\nA-001,10,2026-08-12\n");
        adminService.saveDraft(connector.getId(), adminService.readSchema(connector, csv, 1),
                mappingForms());
        runner.run(new RunRequest(connector.getId(), RunMode.TEST, RunTrigger.MANUAL, csv, 1));

        ConnectorSummary summary = queryService.list(TENANT).stream()
                .filter(row -> row.id().equals(connector.getId()))
                .findFirst().orElseThrow();

        assertThat(summary.readyForLive())
                .as("확정 전에는 테스트만 가능함이 목록에 드러나야 한다").isFalse();
        assertThat(summary.lastStatus()).isEqualTo("SUCCEEDED");
        assertThat(summary.lastSuccess()).isEqualTo(1);
    }

    @Test
    @DisplayName("실행 이력에 사용한 매핑 버전이 남는다")
    void 이력에_매핑_버전이_남는다() throws IOException {
        Connector connector = newConnector();
        Path csv = write("품목코드,수량,기준일\nA-001,10,2026-08-12\n");
        adminService.saveDraft(connector.getId(), adminService.readSchema(connector, csv, 1),
                mappingForms());
        runner.run(new RunRequest(connector.getId(), RunMode.TEST, RunTrigger.MANUAL, csv, 1));

        List<RunSummary> runs = queryService.runs(connector.getId(), 10);

        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().mappingVersion()).isEqualTo(1);
        assertThat(runs.getFirst().isTest()).isTrue();
    }

    @Test
    @DisplayName("실패 행이 원본째 조회된다")
    void 실패_행을_조회한다() throws IOException {
        Connector connector = newConnector();
        Path csv = write("품목코드,수량,기준일\nA-001,없음,2026-08-12\n");
        adminService.saveDraft(connector.getId(), adminService.readSchema(connector, csv, 1),
                mappingForms());

        ConnectorRun run = runner.run(new RunRequest(connector.getId(), RunMode.TEST,
                RunTrigger.MANUAL, csv, 1));

        assertThat(queryService.errors(run.getId(), 10))
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.rowNumber()).isEqualTo(1);
                    assertThat(error.sourceRow()).contains("없음");
                });
    }

    // ---------- 픽스처 ----------

    private Connector newConnector() {
        return adminService.create("c-" + UUID.randomUUID(), "테스트 연동",
                snapshotModel().getId(), SourceType.UPLOAD, "EA");
    }

    private TargetModel snapshotModel() {
        return targetModelRepository.findByTenantIdAndCode(TENANT, "std_stock_snapshot")
                .orElseThrow();
    }

    /**
     * 화면에서 사람이 연결한 결과.
     *
     * <p>{@code source} 는 원천에 없는 값이라 기본값 규칙으로 채운다 — 화면의 "변환" 드롭다운이
     * 하는 일과 같다.
     */
    private List<FieldMappingForm> mappingForms() {
        return List.of(
                new FieldMappingForm("품목코드", "item_ref", "NONE", null, 0),
                new FieldMappingForm("수량", "quantity", "NONE", null, 1),
                new FieldMappingForm("기준일", "base_at", "NONE", null, 2),
                new FieldMappingForm("__none__", "source", "DEFAULT_IF_EMPTY",
                        "SRC-" + UUID.randomUUID().toString().substring(0, 8), 3),
                // 연결하지 않은 줄은 저장되지 않아야 한다
                new FieldMappingForm("품목코드", "", "NONE", null, 4));
    }

    private Path write(String content) throws IOException {
        Path csv = tempDir.resolve("data-" + UUID.randomUUID() + ".csv");
        Files.writeString(csv, content, StandardCharsets.UTF_8);
        return csv;
    }
}
