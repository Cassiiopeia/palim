package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.connector.transform.MappedRow;
import kr.suhsaechan.palim.connector.write.StagingWriter;
import kr.suhsaechan.palim.connector.write.StandardModelWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 적재기.
 *
 * <p>설계가 핵심이라고 지목한 둘을 검증한다 — <b>TEST 가 운영 테이블을 건드리지 않는가</b>,
 * <b>같은 데이터를 두 번 넣어도 중복이 생기지 않는가</b>. 이 둘이 깨지면 사람이 시스템을
 * 믿지 못하고, 믿지 못하면 쓰지 않는다.
 */
class ConnectorWriterIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final Instant BASE_AT = Instant.parse("2026-08-12T00:00:00Z");

    @Autowired private StagingWriter stagingWriter;
    @Autowired private StandardModelWriter standardWriter;
    @Autowired private TargetModelRepository targetModelRepository;
    @Autowired private JdbcClient jdbcClient;

    @Test
    @DisplayName("TEST 적재는 표준 테이블을 건드리지 않는다")
    void 테스트_적재는_운영을_건드리지_않는다() {
        String itemRef = uniqueItem();
        UUID runId = UUID.randomUUID();

        stagingWriter.write(TENANT, runId, snapshotModel(), List.of(snapshotRow(itemRef, "10")));

        assertThat(countStaging(runId)).isEqualTo(1);
        assertThat(countSnapshot(itemRef))
                .as("지우기 전에 도메인 로직이 읽으면 오염된 결과가 나온다").isZero();
    }

    @Test
    @DisplayName("스테이징 삭제는 그 실행분만 지운다")
    void 스테이징을_실행_단위로_지운다() {
        UUID keep = UUID.randomUUID();
        UUID drop = UUID.randomUUID();
        stagingWriter.write(TENANT, keep, snapshotModel(), List.of(snapshotRow(uniqueItem(), "10")));
        stagingWriter.write(TENANT, drop, snapshotModel(), List.of(snapshotRow(uniqueItem(), "20")));

        jdbcClient.sql("DELETE FROM connector_staging WHERE run_id = :id")
                .param("id", drop).update();

        assertThat(countStaging(drop)).isZero();
        assertThat(countStaging(keep)).as("다른 실행분은 남아야 한다").isEqualTo(1);
    }

    @Test
    @DisplayName("LIVE 적재가 표준 테이블에 들어간다")
    void 표준_테이블에_적재한다() {
        String itemRef = uniqueItem();

        standardWriter.write(TENANT, UUID.randomUUID(), snapshotModel(),
                List.of(snapshotRow(itemRef, "10")));

        assertThat(countSnapshot(itemRef)).isEqualTo(1);
        assertThat(quantityOf(itemRef)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("같은 자연키를 두 번 적재해도 행이 늘지 않고 값이 갱신된다")
    void 재적재가_중복을_만들지_않는다() {
        String itemRef = uniqueItem();

        standardWriter.write(TENANT, UUID.randomUUID(), snapshotModel(),
                List.of(snapshotRow(itemRef, "10")));
        standardWriter.write(TENANT, UUID.randomUUID(), snapshotModel(),
                List.of(snapshotRow(itemRef, "25")));

        assertThat(countSnapshot(itemRef))
                .as("재시도가 안전해야 사람이 자동화를 켠다").isEqualTo(1);
        assertThat(quantityOf(itemRef)).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("자연키의 NULL 컬럼도 같은 값으로 취급한다")
    void 자연키의_NULL_을_구분하지_않는다() {
        String itemRef = uniqueItem();
        // lot_code 와 warehouse_code 가 없는 행. NULLS NOT DISTINCT 가 없으면 매번 새 행이 된다.
        MappedRow row = snapshotRow(itemRef, "10");

        standardWriter.write(TENANT, UUID.randomUUID(), snapshotModel(), List.of(row));
        standardWriter.write(TENANT, UUID.randomUUID(), snapshotModel(), List.of(row));

        assertThat(countSnapshot(itemRef)).isEqualTo(1);
    }

    @Test
    @DisplayName("처음 만든 행은 undo 로그에 이전 값이 없다 — 되돌리기는 삭제다")
    void 신규_행은_이전값이_없다() {
        String itemRef = uniqueItem();
        UUID runId = UUID.randomUUID();

        standardWriter.write(TENANT, runId, snapshotModel(), List.of(snapshotRow(itemRef, "10")));

        assertThat(countUndo(runId)).as("대상 행이 없었으므로 undo 행도 없다").isZero();
    }

    @Test
    @DisplayName("덮어쓴 행은 undo 로그에 이전 값이 남는다")
    void 덮어쓰면_이전값을_남긴다() {
        String itemRef = uniqueItem();
        standardWriter.write(TENANT, UUID.randomUUID(), snapshotModel(),
                List.of(snapshotRow(itemRef, "10")));

        UUID secondRun = UUID.randomUUID();
        standardWriter.write(TENANT, secondRun, snapshotModel(),
                List.of(snapshotRow(itemRef, "25")));

        assertThat(countUndo(secondRun)).isEqualTo(1);
        assertThat(previousQuantity(secondRun)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("한 실행에 같은 자연키가 두 번 와도 undo 는 하나만 남는다")
    void undo_는_실행당_하나다() {
        String itemRef = uniqueItem();
        standardWriter.write(TENANT, UUID.randomUUID(), snapshotModel(),
                List.of(snapshotRow(itemRef, "10")));

        UUID runId = UUID.randomUUID();
        standardWriter.write(TENANT, runId, snapshotModel(),
                List.of(snapshotRow(itemRef, "20"), snapshotRow(itemRef, "30")));

        assertThat(countUndo(runId)).isEqualTo(1);
        assertThat(previousQuantity(runId))
                .as("두 번째 undo 가 남으면 복원이 최초가 아니라 중간 상태로 간다")
                .isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("매핑되지 않은 컬럼은 attributes 에 보존된다")
    void attributes_를_보존한다() {
        String itemRef = uniqueItem();
        MappedRow row = new MappedRow(1, snapshotValues(itemRef, "10"),
                Map.of("공급처", "합성공급처", "비고", "메모"));

        standardWriter.write(TENANT, UUID.randomUUID(), snapshotModel(), List.of(row));

        String attributes = jdbcClient.sql(
                        "SELECT attributes::text FROM std_stock_snapshot WHERE item_ref = :ref")
                .param("ref", itemRef).query(String.class).single();
        assertThat(attributes).contains("공급처", "비고");
    }

    private TargetModel snapshotModel() {
        return targetModelRepository.findByTenantIdAndCode(TENANT, "std_stock_snapshot")
                .orElseThrow();
    }

    private String uniqueItem() {
        return "IT-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private MappedRow snapshotRow(String itemRef, String quantity) {
        return new MappedRow(1, snapshotValues(itemRef, quantity), Map.of());
    }

    /** 자연키(source·base_at·item_ref·warehouse_code·lot_code)와 NOT NULL 컬럼을 채운다. */
    private Map<String, Object> snapshotValues(String itemRef, String quantity) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("source", "TEST_SOURCE");
        values.put("base_at", BASE_AT);
        values.put("item_ref", itemRef);
        values.put("quantity", new BigDecimal(quantity));
        values.put("base_quantity", new BigDecimal(quantity));
        values.put("base_unit", "EA");
        return values;
    }

    private int countStaging(UUID runId) {
        return jdbcClient.sql("SELECT count(*)::int FROM connector_staging WHERE run_id = :id")
                .param("id", runId).query(Integer.class).single();
    }

    private int countSnapshot(String itemRef) {
        return jdbcClient.sql(
                        "SELECT count(*)::int FROM std_stock_snapshot WHERE item_ref = :ref")
                .param("ref", itemRef).query(Integer.class).single();
    }

    private BigDecimal quantityOf(String itemRef) {
        return jdbcClient.sql("SELECT quantity FROM std_stock_snapshot WHERE item_ref = :ref")
                .param("ref", itemRef).query(BigDecimal.class).single();
    }

    private int countUndo(UUID runId) {
        return jdbcClient.sql("SELECT count(*)::int FROM connector_undo_log WHERE run_id = :id")
                .param("id", runId).query(Integer.class).single();
    }

    /** numeric(19,3) 이라 "10" 이 "10.000" 으로 저장된다. 수치로 비교해야 한다. */
    private BigDecimal previousQuantity(UUID runId) {
        return jdbcClient.sql("""
                        SELECT (previous_row ->> 'quantity')::numeric FROM connector_undo_log
                        WHERE run_id = :id
                        """)
                .param("id", runId).query(BigDecimal.class).single();
    }
}
