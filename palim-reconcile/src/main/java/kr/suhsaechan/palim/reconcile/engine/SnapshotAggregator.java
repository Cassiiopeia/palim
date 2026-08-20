package kr.suhsaechan.palim.reconcile.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.reconcile.define.CompareField;
import kr.suhsaechan.palim.reconcile.define.WarehouseScope;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 확정된 정합 단위로 스냅샷을 합산한다.
 *
 * <p>이 클래스는 <b>SQL 만 안다.</b> 무엇을 비교할지, 차이를 어떻게 분류할지는 모른다. 셋을 한
 * 클래스에 두면 «허용 오차를 고쳤는데 합산이 깨지는» 일이 생긴다.
 *
 * <p>표준 모델 테이블을 직접 읽는다 — 원천이 API 인지 엑셀인지 알 필요가 없고, 알면 안 된다.
 * 대조가 수집 방식을 알기 시작하면 원천이 늘 때마다 대조를 고쳐야 한다.
 */
@Component
@RequiredArgsConstructor
public class SnapshotAggregator {

    private final JdbcClient jdbcClient;

    /**
     * 단위별 합계.
     *
     * @param compareField 합산할 수치 칸. 허용 목록에 없으면 기본값으로 되돌린다
     * @return 정합 단위 → 환산 합계. 확정된 연결이 없으면 빈 결과
     */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> sumByUnit(UUID tenantId, String source, Instant baseAt,
                                           String compareField) {
        return sumByUnit(tenantId, source, baseAt, compareField, WarehouseScope.all());
    }

    /**
     * @param scope 볼 창고. 비어 있으면 전부 — 전부 더하면 위탁하지 않은 물량까지 섞인다
     */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> sumByUnit(UUID tenantId, String source, Instant baseAt,
                                           String compareField, WarehouseScope scope) {
        // 허용 목록은 define/CompareField 한 곳에만 둔다 — 두 벌이 되면 한쪽만 늘어난다.
        String column = CompareField.sanitize(compareField);

        List<Map.Entry<UUID, BigDecimal>> rows = jdbcClient.sql("""
                        SELECT m.unit_id AS unit_id, coalesce(sum(s.%s * m.factor), 0) AS qty
                          FROM std_stock_snapshot s
                          JOIN reconcile_unit_member m
                            ON m.tenant_id = s.tenant_id
                           AND m.source    = s.source
                           AND m.item_ref  = s.item_ref
                           AND m.confirmed_at IS NOT NULL
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.base_at   = :baseAt%s
                         GROUP BY m.unit_id
                        """.formatted(column, scope.sqlAnd("s")))
                .param("tenantId", tenantId)
                .param("source", source)
                // JdbcClient 는 Instant 를 바인딩하지 못한다. timestamptz 에는 OffsetDateTime.
                .param("baseAt", baseAt.atOffset(ZoneOffset.UTC))
                .params(scope.params())
                .query((rs, rowNum) -> Map.entry(
                        rs.getObject("unit_id", UUID.class),
                        rs.getBigDecimal("qty")))
                .list();

        Map<UUID, BigDecimal> sums = new LinkedHashMap<>();
        rows.forEach(entry -> sums.put(entry.getKey(), entry.getValue()));
        return sums;
    }

    /**
     * 어느 단위에도 속하지 않은 품목.
     *
     * <p>이것을 «없는 셈» 치면 재고가 있는데도 대조에서 사라진다. 미매칭으로 남겨야 사람이
     * 연결할 것이 있다는 사실을 안다.
     */
    @Transactional(readOnly = true)
    public List<UnmatchedItem> unmatched(UUID tenantId, String source, Instant baseAt,
                                         String compareField) {
        return unmatched(tenantId, source, baseAt, compareField, WarehouseScope.all());
    }

    /**
     * @param scope 볼 창고. 합계와 <b>같은 범위</b>여야 한다 — 한쪽만 걸러지면 「합계는 이런데
     *              뜯어보면 다르다」 가 되어 어느 쪽을 믿어야 할지 알 수 없다
     */
    @Transactional(readOnly = true)
    public List<UnmatchedItem> unmatched(UUID tenantId, String source, Instant baseAt,
                                         String compareField, WarehouseScope scope) {
        // 허용 목록은 define/CompareField 한 곳에만 둔다 — 두 벌이 되면 한쪽만 늘어난다.
        String column = CompareField.sanitize(compareField);

        return jdbcClient.sql("""
                        SELECT s.item_ref AS item_ref,
                               max(coalesce(s.raw_item_name, '')) AS raw_name,
                               coalesce(sum(s.%s), 0) AS qty
                          FROM std_stock_snapshot s
                          LEFT JOIN reconcile_unit_member m
                            ON m.tenant_id = s.tenant_id
                           AND m.source    = s.source
                           AND m.item_ref  = s.item_ref
                           AND m.confirmed_at IS NOT NULL
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.base_at   = :baseAt
                           AND m.id IS NULL%s
                         GROUP BY s.item_ref
                        """.formatted(column, scope.sqlAnd("s")))
                .param("tenantId", tenantId)
                .param("source", source)
                .param("baseAt", baseAt.atOffset(ZoneOffset.UTC))
                .params(scope.params())
                .query((rs, rowNum) -> new UnmatchedItem(
                        rs.getString("item_ref"),
                        rs.getString("raw_name"),
                        rs.getBigDecimal("qty")))
                .list();
    }

    /**
     * 아직 연결되지 않은 품목 하나.
     *
     * @param itemRef  원천 품목 식별자
     * @param rawName  원본 품명. 사람이 무엇인지 알아보려면 코드만으로는 부족하다
     * @param quantity 그 품목의 수량
     */
    public record UnmatchedItem(String itemRef, String rawName, BigDecimal quantity) {
    }

    /**
     * 이 원천에 <b>실제로 들어온</b> 창고.
     *
     * <p>커넥터 설정이 아니라 담긴 자료에서 뽑는다 — 설정에만 있고 자료가 없는 창고를 고르면
     * 대조 대상이 통째로 비는데, 화면은 「고르긴 골랐다」 고 보이므로 원인을 찾기 어렵다.
     *
     * <p>수량을 함께 준다. 어느 창고가 맡긴 분인지는 <b>규모로 판단</b>하게 되기 때문이다 —
     * 이름만으로는 「사무실 창고」 와 「정도로지스」 중 어느 쪽이 위탁인지 알 수 없다.
     *
     * <p>가장 최근 기준 시각의 자료만 본다. 옛 회차에만 있던 창고가 목록에 남으면 지금은 쓰지
     * 않는 창고를 고르게 된다.
     */
    @Transactional(readOnly = true)
    public List<Warehouse> warehouses(UUID tenantId, String source) {
        return jdbcClient.sql("""
                        SELECT coalesce(s.warehouse_code, '')                AS code,
                               max(coalesce(s.warehouse_name, ''))           AS name,
                               count(*)::int                                 AS items,
                               sum(s.base_quantity)                          AS qty
                          FROM std_stock_snapshot s
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.base_at   = (SELECT max(x.base_at) FROM std_stock_snapshot x
                                               WHERE x.tenant_id = :tenantId AND x.source = :source)
                         GROUP BY coalesce(s.warehouse_code, '')
                         ORDER BY sum(s.base_quantity) DESC
                        """)
                .param("tenantId", tenantId)
                .param("source", source)
                .query((rs, rowNum) -> new Warehouse(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getInt("items"),
                        rs.getBigDecimal("qty")))
                .list();
    }

    /**
     * 담긴 자료에 있는 창고 하나.
     *
     * @param code  창고 코드. 원천이 창고를 안 주면 빈 문자열이다
     * @param name  창고 이름. 코드만 오는 원천도 있어 비어 있을 수 있다
     * @param items 그 창고에 있는 품목 줄 수
     * @param qty   그 창고의 수량 합계. 어느 창고가 맡긴 분인지 규모로 판단하게 된다
     */
    public record Warehouse(String code, String name, int items, BigDecimal qty) {

        /** 화면에 쓸 이름. 이름이 없으면 코드로 대신한다 — 빈 칸은 고를 수 없다. */
        public String label() {
            if (name != null && !name.isBlank()) {
                return code.isBlank() ? name : "%s (%s)".formatted(name, code);
            }
            return code.isBlank() ? "창고 구분 없음" : code;
        }
    }

    /** 이 원천에 스냅샷이 있는 가장 최근 기준 시각. 없으면 비어 있다. */
    @Transactional(readOnly = true)
    public java.util.Optional<Instant> latestBaseAt(UUID tenantId, String source) {
        return jdbcClient.sql("""
                        SELECT max(base_at) FROM std_stock_snapshot
                         WHERE tenant_id = :tenantId AND source = :source
                        """)
                .param("tenantId", tenantId)
                .param("source", source)
                .query((rs, rowNum) -> {
                    var value = rs.getObject(1, java.time.OffsetDateTime.class);
                    return value == null ? null : value.toInstant();
                })
                .optional()
                .flatMap(java.util.Optional::ofNullable);
    }
}
