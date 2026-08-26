package kr.suhsaechan.palim.reconcile.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.reconcile.define.CompareField;
import kr.suhsaechan.palim.reconcile.filter.FilterSpec;
import kr.suhsaechan.palim.reconcile.filter.FilterableField;
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
        return sumByUnit(tenantId, source, baseAt, compareField, FilterSpec.all());
    }

    /**
     * @param filter 볼 조건. 비어 있으면 전부 — 전부 더하면 맡기지 않은 물량까지 섞인다
     */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> sumByUnit(UUID tenantId, String source, Instant baseAt,
                                           String compareField, FilterSpec filter) {
        // 허용 목록은 define/CompareField 한 곳에만 둔다 — 두 벌이 되면 한쪽만 늘어난다.
        String column = CompareField.sanitize(compareField);
        // 조각과 값을 한 번에 만든다. 따로 만들면 상대 날짜(「오늘+30」)가 두 시각으로 풀려
        // 조각과 값이 어긋날 수 있다 — 그 어긋남은 자정을 넘길 때만 나타나 재현이 어렵다.
        FilterSpec.Compiled where = filter.compile("s", FilterSpec.PREFIX, baseAt);

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
                        """.formatted(column, where.sql()))
                .param("tenantId", tenantId)
                .param("source", source)
                // JdbcClient 는 Instant 를 바인딩하지 못한다. timestamptz 에는 OffsetDateTime.
                .param("baseAt", baseAt.atOffset(ZoneOffset.UTC))
                .params(where.params())
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
        return unmatched(tenantId, source, baseAt, compareField, FilterSpec.all());
    }

    /**
     * @param filter 볼 조건. 합계와 <b>같은 범위</b>여야 한다 — 한쪽만 걸러지면 「합계는 이런데
     *              뜯어보면 다르다」 가 되어 어느 쪽을 믿어야 할지 알 수 없다
     */
    @Transactional(readOnly = true)
    public List<UnmatchedItem> unmatched(UUID tenantId, String source, Instant baseAt,
                                         String compareField, FilterSpec filter) {
        // 허용 목록은 define/CompareField 한 곳에만 둔다 — 두 벌이 되면 한쪽만 늘어난다.
        String column = CompareField.sanitize(compareField);
        FilterSpec.Compiled where = filter.compile("s", FilterSpec.PREFIX, baseAt);

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
                        """.formatted(column, where.sql()))
                .param("tenantId", tenantId)
                .param("source", source)
                .param("baseAt", baseAt.atOffset(ZoneOffset.UTC))
                .params(where.params())
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
     * 이 원천에 <b>실제로 들어온</b> 값들.
     *
     * <p>커넥터 설정이 아니라 담긴 자료에서 뽑는다 — 설정에만 있고 자료가 없는 값을 고르면
     * 대조 대상이 통째로 비는데, 화면은 「고르긴 골랐다」 고 보이므로 원인을 찾기 어렵다.
     *
     * <p>수량을 함께 준다. 어느 창고가 맡긴 분인지는 <b>규모로 판단</b>하게 되기 때문이다 —
     * 이름만으로는 알 수 없다.
     *
     * <p>가장 최근 기준 시각의 자료만 본다. 옛 회차에만 있던 값이 목록에 남으면 지금은 쓰지
     * 않는 것을 고르게 된다.
     *
     * <p>표현식은 <b>카탈로그를 거친 것만</b> 온다 — 부르는 쪽이 임의 문자열을 넘길 수 없다.
     */
    @Transactional(readOnly = true)
    public List<FieldValue> valuesOf(UUID tenantId, String source, FilterableField field) {
        String column = field.sqlWith("s");
        // 창고는 이름 칸이 따로 있다. 그 칸이 있으면 함께 보여 사람이 알아볼 수 있게 한다 —
        // 「W-01」 만 보고 어느 창고인지 아는 사람은 없다.
        String labelColumn = "warehouse_code".equals(field.key())
                ? "max(coalesce(s.warehouse_name, ''))" : "''";

        return jdbcClient.sql("""
                        SELECT coalesce(%s, '')     AS value,
                               %s                   AS label,
                               count(*)::int        AS items,
                               sum(s.base_quantity) AS qty
                          FROM std_stock_snapshot s
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.base_at   = (SELECT max(x.base_at) FROM std_stock_snapshot x
                                               WHERE x.tenant_id = :tenantId
                                                 AND x.source    = :source)
                         GROUP BY coalesce(%s, '')
                         ORDER BY sum(s.base_quantity) DESC
                         LIMIT %d
                        """.formatted(column, labelColumn, column, VALUE_LIMIT + 1))
                .param("tenantId", tenantId)
                .param("source", source)
                .query((rs, rowNum) -> new FieldValue(
                        rs.getString("value"), rs.getString("label"),
                        rs.getInt("items"), rs.getBigDecimal("qty")))
                .list();
    }

    /**
     * 값 후보를 몇 개까지 보여줄지.
     *
     * <p>품목코드처럼 값이 수만 개인 칸을 고르면 화면이 그것을 전부 그린다. 상한을 하나 더 받아
     * 와서 <b>「더 있다」 를 화면이 말할 수 있게</b> 한다 — 말없이 자르면 목록에 없는 값은 없는
     * 값으로 읽힌다.
     */
    public static final int VALUE_LIMIT = 200;

    /**
     * 걸 수 있는 값 하나.
     *
     * @param value 저장될 값. 원천이 그 칸을 안 주면 빈 문자열이다
     * @param label 곁들일 이름. 창고처럼 이름 칸이 따로 있는 경우에만 채워진다
     * @param items 그 값을 가진 품목 줄 수
     * @param qty   그 값의 수량 합계. <b>무엇을 골라야 하는지 규모로 판단하게 된다</b>
     */
    public record FieldValue(String value, String label, int items, BigDecimal qty) {

        /** 화면에 쓸 이름. 이름이 없으면 값으로 대신한다 — 빈 칸은 고를 수 없다. */
        public String display() {
            if (label != null && !label.isBlank()) {
                return value.isBlank() ? label : "%s (%s)".formatted(label, value);
            }
            return value.isBlank() ? "값 없음" : value;
        }
    }

    /**
     * 이 원천이 주는 <b>표준에 없는 칸</b>의 이름들.
     *
     * <p>매핑되지 않은 원천 컬럼을 {@code attributes} 에 통째로 살려 두므로, 그 키를 뽑으면
     * <b>원천 계정이 바뀌어 칸 구성이 달라져도</b> 화면이 그대로 동작한다. 코드에 칸 이름을 박지
     * 않는 이유가 이것이다.
     */
    @Transactional(readOnly = true)
    public List<String> attributeKeys(UUID tenantId, String source) {
        return jdbcClient.sql("""
                        SELECT DISTINCT k AS key
                          FROM std_stock_snapshot s,
                               LATERAL jsonb_object_keys(s.attributes) AS k
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.base_at   = (SELECT max(x.base_at) FROM std_stock_snapshot x
                                               WHERE x.tenant_id = :tenantId
                                                 AND x.source    = :source)
                         ORDER BY k
                         LIMIT 200
                        """)
                .param("tenantId", tenantId)
                .param("source", source)
                .query((rs, rowNum) -> rs.getString("key"))
                .list();
    }

    /**
     * 이 조건이면 몇 줄이 남는가.
     *
     * <p><b>저장 전에 보여준다.</b> 이것이 없으면 저장하고 대조를 돌려 봐야 결과를 안다 —
     * 그리고 그때는 이미 지난 회차의 숫자가 바뀐 뒤다.
     */
    @Transactional(readOnly = true)
    public Preview preview(UUID tenantId, String source, FilterSpec filter, Instant asOf) {
        // 조각을 두 자리에 쓰지만 바인딩은 한 벌이다. 이름을 순번으로 뽑는 덕에 성립한다.
        FilterSpec.Compiled where = filter.compile("s", FilterSpec.PREFIX, asOf);

        return jdbcClient.sql("""
                        SELECT count(*)::int AS total,
                               count(*) FILTER (WHERE true%s)::int AS kept,
                               coalesce(sum(s.base_quantity) FILTER (WHERE true%s), 0) AS kept_qty
                          FROM std_stock_snapshot s
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.base_at   = (SELECT max(x.base_at) FROM std_stock_snapshot x
                                               WHERE x.tenant_id = :tenantId
                                                 AND x.source    = :source)
                        """.formatted(where.sql(), where.sql()))
                .param("tenantId", tenantId)
                .param("source", source)
                .params(where.params())
                .query((rs, rowNum) -> new Preview(
                        rs.getInt("total"), rs.getInt("kept"), rs.getBigDecimal("kept_qty")))
                .single();
    }

    /**
     * 조건을 걸었을 때 남는 것.
     *
     * @param totalItems 조건 없이 담긴 줄 수
     * @param keptItems  조건을 걸고 남는 줄 수
     * @param keptQty    남는 줄의 수량 합계
     */
    public record Preview(int totalItems, int keptItems, BigDecimal keptQty) {
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

    /**
     * <b>그 날짜 안에서</b> 가장 나중 시각.
     *
     * <p>「전일 기준」 이 이것이다. 날짜를 안 좁히면 언제나 «가장 최신» 을 보는데, 아침에 도는
     * 대조가 그러면 <b>오늘 새벽에 들어온 자료</b>를 견준다 — 어제치를 보려던 것과 다른 답이
     * 나오고, 그것은 <b>틀린 값이 아니라 기준이 다른 값</b>이라 아무도 눈치채지 못한다.
     *
     * @param date 지역 날짜. 그날 0시부터 다음 날 0시 직전까지를 본다
     */
    public java.util.Optional<Instant> latestBaseAtOn(UUID tenantId, String source,
                                                      java.time.LocalDate date,
                                                      java.time.ZoneId zone) {
        java.time.OffsetDateTime from = date.atStartOfDay(zone).toOffsetDateTime();
        java.time.OffsetDateTime to = date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        return jdbcClient.sql("""
                        SELECT max(base_at) FROM std_stock_snapshot
                         WHERE tenant_id = :tenantId AND source = :source
                           AND base_at >= :from AND base_at < :to
                        """)
                .param("tenantId", tenantId)
                .param("source", source)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> {
                    var value = rs.getObject(1, java.time.OffsetDateTime.class);
                    return value == null ? null : value.toInstant();
                })
                .optional()
                .flatMap(java.util.Optional::ofNullable);
    }
}
