package kr.suhsaechan.palim.reconcile.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    /**
     * 비교할 수 있는 수치 칸.
     *
     * <p>칸 이름이 SQL 에 그대로 들어가는 자리라 <b>허용 목록으로 거른다.</b> 정의 화면이
     * 막더라도 여기서 한 번 더 막는다 — 뚫리면 조회 범위가 통째로 열린다.
     */
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "base_quantity", "quantity", "available_quantity", "reserved_quantity", "amount");

    private static final String DEFAULT_FIELD = "base_quantity";

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
        String column = ALLOWED_FIELDS.contains(compareField) ? compareField : DEFAULT_FIELD;

        List<Map.Entry<UUID, BigDecimal>> rows = jdbcClient.sql("""
                        SELECT m.unit_id AS unit_id, sum(s.%s * m.factor) AS qty
                          FROM std_stock_snapshot s
                          JOIN reconcile_unit_member m
                            ON m.tenant_id = s.tenant_id
                           AND m.source    = s.source
                           AND m.item_ref  = s.item_ref
                           AND m.confirmed_at IS NOT NULL
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.base_at   = :baseAt
                         GROUP BY m.unit_id
                        """.formatted(column))
                .param("tenantId", tenantId)
                .param("source", source)
                // JdbcClient 는 Instant 를 바인딩하지 못한다. timestamptz 에는 OffsetDateTime.
                .param("baseAt", baseAt.atOffset(ZoneOffset.UTC))
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
        String column = ALLOWED_FIELDS.contains(compareField) ? compareField : DEFAULT_FIELD;

        return jdbcClient.sql("""
                        SELECT s.item_ref AS item_ref,
                               max(coalesce(s.raw_item_name, '')) AS raw_name,
                               sum(s.%s) AS qty
                          FROM std_stock_snapshot s
                          LEFT JOIN reconcile_unit_member m
                            ON m.tenant_id = s.tenant_id
                           AND m.source    = s.source
                           AND m.item_ref  = s.item_ref
                           AND m.confirmed_at IS NOT NULL
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.base_at   = :baseAt
                           AND m.id IS NULL
                         GROUP BY s.item_ref
                        """.formatted(column))
                .param("tenantId", tenantId)
                .param("source", source)
                .param("baseAt", baseAt.atOffset(ZoneOffset.UTC))
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
