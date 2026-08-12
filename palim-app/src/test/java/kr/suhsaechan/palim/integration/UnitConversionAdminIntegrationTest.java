package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.connector.run.ConnectorRun;
import kr.suhsaechan.palim.connector.run.ConnectorRunner;
import kr.suhsaechan.palim.connector.run.RunMode;
import kr.suhsaechan.palim.connector.run.RunRequest;
import kr.suhsaechan.palim.connector.run.RunStatus;
import kr.suhsaechan.palim.connector.run.RunTrigger;
import kr.suhsaechan.palim.connector.unit.UnitConversionRepository;
import kr.suhsaechan.palim.web.connector.ConnectorAdminService;
import kr.suhsaechan.palim.web.connector.FieldMappingForm;
import kr.suhsaechan.palim.web.connector.UnitConversionAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 단위 환산 규칙 화면.
 *
 * <p>핵심은 <b>막힌 것을 화면에서 풀 수 있는가</b>다. 막는 장치를 만들었으면 푸는 수단도 같이
 * 있어야 하고, 없으면 사람이 DB 를 직접 만져야 한다.
 */
class UnitConversionAdminIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = ConnectorAdminService.DEFAULT_TENANT;

    @TempDir Path tempDir;

    @Autowired private UnitConversionAdminService unitService;
    @Autowired private ConnectorAdminService adminService;
    @Autowired private UnitConversionRepository conversionRepository;
    @Autowired private ConnectorRunner runner;
    @Autowired private TargetModelRepository targetModelRepository;

    @Test
    @DisplayName("단위 때문에 막힌 적재를 규칙 등록으로 푼다")
    void 규칙을_넣으면_적재가_풀린다() throws IOException {
        Connector connector = newConnector();
        // 원천이 BOX 단위를 명시했다. 규칙이 없으면 엔진이 막는다.
        Path csv = write("""
                품목코드,수량,단위,기준일
                A-001,12,BOX,2026-08-12
                """);
        adminService.saveDraft(connector.getId(), adminService.readSchema(connector, csv, 1),
                mappingForms());

        ConnectorRun blocked = runner.run(new RunRequest(connector.getId(), RunMode.TEST,
                RunTrigger.MANUAL, csv, 1));
        assertThat(blocked.getFailedCount())
                .as("조용히 1:1 로 넘기면 BOX 12개가 EA 12개로 둔갑한다").isEqualTo(1);

        // 화면에서 규칙을 등록한다
        unitService.create(null, "BOX", "EA", new BigDecimal("12"));

        ConnectorRun passed = runner.run(new RunRequest(connector.getId(), RunMode.TEST,
                RunTrigger.MANUAL, csv, 1));

        assertThat(passed.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(passed.getSuccessCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("전역 규칙과 품목별 규칙이 함께 등록된다")
    void 전역과_품목별이_공존한다() {
        String from = "U" + UUID.randomUUID().toString().substring(0, 6);

        unitService.create(null, from, "EA", new BigDecimal("10"));
        unitService.create("특정품목", from, "EA", new BigDecimal("5"));

        List<?> rules = unitService.list();
        assertThat(rules).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("품목별 규칙이 전역 규칙보다 앞선다")
    void 품목별_규칙이_우선한다() {
        // 우선순위는 JPQL 의 order by 가 만든다. 모의 객체로는 증명되지 않으므로 실제 DB 로
        // 확인한다 — 순서가 뒤집히면 품목별 예외를 등록해도 전역 배율로 환산되고,
        // 그 오류는 수량이 이상해질 때까지 드러나지 않는다.
        String from = "P" + UUID.randomUUID().toString().substring(0, 6);
        String item = "ITEM-" + UUID.randomUUID();

        unitService.create(null, from, "EA", new BigDecimal("10"));
        unitService.create(item, from, "EA", new BigDecimal("5"));

        assertThat(conversionRepository.findRule(TENANT, item, from, "EA"))
                .as("품목별 규칙이 있으면 그것을 쓴다")
                .contains(new BigDecimal("5.000000"));

        assertThat(conversionRepository.findRule(TENANT, "다른품목-" + UUID.randomUUID(),
                from, "EA"))
                .as("품목별 규칙이 없는 품목은 전역 규칙으로 떨어진다")
                .contains(new BigDecimal("10.000000"));
    }

    @Test
    @DisplayName("품목을 비워 등록한 규칙은 전역 규칙과 같은 것으로 본다")
    void 빈_품목은_전역과_같다() {
        // 화면에서 품목 칸을 비우면 null 또는 공백 문자열이 올라온다. 이것들이 서로 다른
        // 규칙으로 저장되면 전역 규칙이 여러 벌 생기고 중복 검사가 뚫린다.
        String from = "B" + UUID.randomUUID().toString().substring(0, 6);

        unitService.create(null, from, "EA", new BigDecimal("10"));

        assertThatThrownBy(() -> unitService.create("   ", from, "EA", new BigDecimal("20")))
                .as("공백 품목은 전역 규칙과 같은 범위다")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("같은 규칙을 두 번 등록할 수 없다")
    void 중복_규칙을_막는다() {
        String from = "U" + UUID.randomUUID().toString().substring(0, 6);
        unitService.create(null, from, "EA", new BigDecimal("10"));

        assertThatThrownBy(() -> unitService.create(null, from, "EA", new BigDecimal("20")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("배율이 0 이하면 거부한다")
    void 잘못된_배율을_막는다() {
        assertThatThrownBy(() -> unitService.create(null, "BOX", "EA", BigDecimal.ZERO))
                .as("0 배율은 수량을 조용히 0 으로 만든다")
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> unitService.create(null, "BOX", "EA", new BigDecimal("-1")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("같은 단위끼리는 규칙을 만들 수 없다")
    void 같은_단위는_거부한다() {
        assertThatThrownBy(() -> unitService.create(null, "EA", "EA", new BigDecimal("1")))
                .isInstanceOf(BusinessException.class);
    }

    private Connector newConnector() {
        return adminService.create("u-" + UUID.randomUUID(), "단위 테스트 연동",
                targetModelRepository.findByTenantIdAndCode(TENANT, "std_stock_snapshot")
                        .orElseThrow().getId(),
                SourceType.UPLOAD, "EA");
    }

    private List<FieldMappingForm> mappingForms() {
        return List.of(
                new FieldMappingForm("품목코드", "item_ref", "NONE", null, 0),
                new FieldMappingForm("수량", "quantity", "NONE", null, 1),
                new FieldMappingForm("단위", "unit", "NONE", null, 2),
                new FieldMappingForm("기준일", "base_at", "NONE", null, 3),
                new FieldMappingForm("__none__", "source", "DEFAULT_IF_EMPTY",
                        "SRC-" + UUID.randomUUID().toString().substring(0, 8), 4));
    }

    private Path write(String content) throws IOException {
        Path csv = tempDir.resolve("data-" + UUID.randomUUID() + ".csv");
        Files.writeString(csv, content, StandardCharsets.UTF_8);
        return csv;
    }
}
