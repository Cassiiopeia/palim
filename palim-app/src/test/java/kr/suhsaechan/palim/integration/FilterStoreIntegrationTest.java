package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.filter.FilterCompiler;
import kr.suhsaechan.palim.reconcile.filter.FilterOperator;
import kr.suhsaechan.palim.reconcile.filter.FilterRow;
import kr.suhsaechan.palim.reconcile.filter.FilterRowRepository;
import kr.suhsaechan.palim.reconcile.filter.FilterSide;
import kr.suhsaechan.palim.reconcile.filter.FilterSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 조건 줄을 담고 다시 읽는다.
 *
 * <p>창고만 고를 수 있던 시절의 설정이 <b>그대로 살아나야</b> 한다. 이관에서 조건이 사라지면
 * 대조는 다음 날 아침부터 전 창고를 더하는데, 화면은 아무 말도 하지 않는다.
 */
class FilterStoreIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final Instant AS_OF = Instant.parse("2026-08-22T03:00:00Z");

    @Autowired private FilterRowRepository rows;
    @Autowired private FilterCompiler compiler;
    @Autowired private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("조건 줄을 담고 다시 읽으면 값이 그대로다")
    void storesAndReads() {
        UUID definitionId = insertDefinition(null, null);
        rows.save(FilterRow.field(TENANT, definitionId, FilterSide.LEFT, 0,
                "warehouse_code", FilterOperator.IN, List.of("01", "02")));
        rows.save(FilterRow.field(TENANT, definitionId, FilterSide.LEFT, 1,
                "quality_status", FilterOperator.NOT_IN, List.of("불량")));

        List<FilterRow> loaded = rows.findByDefinitionIdOrderBySideAscOrdinalAsc(definitionId);

        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).getValues()).containsExactly("01", "02");
        assertThat(loaded.get(1).getOperator()).isEqualTo(FilterOperator.NOT_IN);
    }

    @Test
    @DisplayName("여러 줄은 AND 로 묶인다")
    void compilesRowsToAnd() {
        UUID definitionId = insertDefinition(null, null);
        List<FilterRow> loaded = List.of(
                FilterRow.field(TENANT, definitionId, FilterSide.LEFT, 0,
                        "warehouse_code", FilterOperator.IN, List.of("01")),
                FilterRow.field(TENANT, definitionId, FilterSide.LEFT, 1,
                        "quality_status", FilterOperator.EQ, List.of("정상")));

        FilterSpec spec = compiler.compile(loaded);

        assertThat(spec.sqlAnd("s", "f", AS_OF))
                .isEqualTo(" AND (s.warehouse_code IN (:f0) AND s.quality_status = :f1)");
    }

    @Test
    @DisplayName("식도 조건 줄과 AND 로 함께 걸린다")
    void compilesExpressionAlongsideRows() {
        UUID definitionId = insertDefinition(null, null);
        List<FilterRow> loaded = List.of(
                FilterRow.field(TENANT, definitionId, FilterSide.LEFT, 0,
                        "warehouse_code", FilterOperator.IN, List.of("01")),
                FilterRow.expression(TENANT, definitionId, FilterSide.LEFT, 1,
                        "품질상태 = '정상' 또는 품질상태 비었음"));

        FilterSpec spec = compiler.compile(loaded);

        assertThat(spec.sqlAnd("s", "f", AS_OF))
                .isEqualTo(" AND (s.warehouse_code IN (:f0)"
                        + " AND (s.quality_status = :f1"
                        + " OR coalesce(s.quality_status, '') = ''))");
    }

    @Test
    @DisplayName("줄이 없으면 전부 본다 — 지금까지의 동작이다")
    void emptyRowsMeanAll() {
        assertThat(compiler.compile(List.of()).isAll()).isTrue();
    }

    @Test
    @DisplayName("카탈로그에 없는 칸이 저장되어 있으면 조용히 건너뛰지 않고 드러낸다")
    void unknownFieldIsReported() {
        UUID definitionId = insertDefinition(null, null);
        List<FilterRow> loaded = List.of(FilterRow.field(TENANT, definitionId,
                FilterSide.LEFT, 0, "no_such_column", FilterOperator.EQ, List.of("x")));

        assertThatThrownBy(() -> compiler.compile(loaded))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("정의를 지우면 조건도 함께 사라진다 — 매달리지 않은 줄이 남으면 안 된다")
    void cascadesOnDefinitionDelete() {
        UUID definitionId = insertDefinition(null, null);
        rows.saveAndFlush(FilterRow.field(TENANT, definitionId, FilterSide.LEFT, 0,
                "warehouse_code", FilterOperator.IN, List.of("01")));

        jdbcClient.sql("DELETE FROM reconcile_definition WHERE id = :id")
                .param("id", definitionId).update();

        assertThat(jdbcClient.sql(
                        "SELECT count(*)::int FROM reconcile_filter WHERE definition_id = :id")
                .param("id", definitionId)
                .query(Integer.class).single())
                .isZero();
    }

    @Test
    @DisplayName("V34 의 이관 문장이 옛 창고 설정을 조건 줄로 옮긴다")
    void migratesWarehouseCsv() {
        // 마이그레이션은 컨테이너가 뜰 때 이미 돌았다. 여기서는 «이관 SQL 과 같은 문장» 이
        // 옳은 줄을 만드는지 본다 — 뒤에 만들어진 정의에도 같은 규칙이 적용되어야 한다.
        UUID legacy = insertDefinition("01,02", "99");

        jdbcClient.sql("""
                        INSERT INTO reconcile_filter
                            (id, tenant_id, definition_id, side, ordinal, row_type,
                             field_key, operator, values_json, created_at, updated_at)
                        SELECT gen_random_uuid(), d.tenant_id, d.id, 'LEFT', 0,
                               'FIELD', 'warehouse_code', 'IN',
                               to_jsonb(string_to_array(d.left_warehouses, ',')), now(), now()
                          FROM reconcile_definition d
                         WHERE d.id = :id AND d.left_warehouses IS NOT NULL
                           AND d.left_warehouses <> ''
                        """)
                .param("id", legacy).update();

        List<FilterRow> loaded = rows.findByDefinitionIdOrderBySideAscOrdinalAsc(legacy);

        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getFieldKey()).isEqualTo("warehouse_code");
        assertThat(loaded.get(0).getOperator()).isEqualTo(FilterOperator.IN);
        assertThat(loaded.get(0).getValues()).containsExactly("01", "02");
    }

    private UUID insertDefinition(String leftWarehouses, String rightWarehouses) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO reconcile_definition
                            (id, tenant_id, code, name, left_source, right_source, target_table,
                             compare_field, tolerance, base_at_granularity, is_active,
                             breakdown_axis, unit_name_rule,
                             left_warehouses, right_warehouses, created_at, updated_at)
                        VALUES (:id, :tenant, :code, '시험 대조', 'left-src', 'right-src',
                                'std_stock_snapshot', 'base_quantity', 0, 'DAY', true,
                                'NAME', 'COMMON', :left, :right, now(), now())
                        """)
                .param("id", id)
                .param("tenant", TENANT)
                .param("code", "DEF-" + id.toString().substring(0, 8))
                .param("left", leftWarehouses)
                .param("right", rightWarehouses)
                .update();
        return id;
    }
}
