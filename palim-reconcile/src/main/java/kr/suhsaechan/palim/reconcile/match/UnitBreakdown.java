package kr.suhsaechan.palim.reconcile.match;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 물건 하나를 <b>품목별로 뜯어본다.</b>
 *
 * <p>대조 결과는 물건 단위 합계만 말한다 — 「클래식 850g · 전산 318 · 물류 307 · +11」. 그런데
 * 그 11의 정체는 <b>「물류가 오래된 로트 3종을 이미 0으로 털었고 최신 로트는 정확히 맞는다」</b>
 * 였다. 「재고가 11개 빈다」 와는 전혀 다른 이야기이고, 할 일도 다르다.
 *
 * <p>합계 하나로는 그 구분이 불가능하다. 그래서 줄을 펼치면 <b>이 물건에 든 품목들을 좌·우로
 * 나란히</b> 놓는다.
 *
 * <h2>무엇과 무엇을 같은 줄에 놓나</h2>
 *
 * <p>이름이 닮은 것끼리 짝짓는다. 이때 <b>다듬은 이름을 쓰면 안 된다</b> — 다듬기가 날짜를
 * 떼어 버리므로 한 물건 안의 모든 로트가 같은 이름이 되어 짝을 가릴 수 없다. 여기서는 원본
 * 품명 그대로 견준다.
 *
 * <p>짝은 <b>점수가 높은 쌍부터</b> 차례로 맺는다. 사람이 이미 「이것들은 같은 물건」 이라고
 * 정해 둔 것들이므로 <b>맺을 수 있는 만큼 다 맺고</b>, 한쪽이 남으면 그 줄은 짝 없이 둔다 —
 * 그 남는 줄이 대개 잘못 이어 둔 품목이다.
 */
@Component
@RequiredArgsConstructor
public class UnitBreakdown {

    private final JdbcClient jdbcClient;

    /**
     * @param leftBaseAt  왼쪽 원천에서 합산한 시각. {@code null} 이면 {@code before} 이전의
     *                    가장 최근 것으로 되짚는다
     * @param before      옛 회차를 되짚을 때 쓸 기준. 보통 회차 시작 시각
     */
    @Transactional(readOnly = true)
    public Breakdown of(UUID tenantId, UUID unitId, String leftSource, String rightSource,
                        Instant leftBaseAt, Instant rightBaseAt, Instant before) {
        boolean exact = leftBaseAt != null && rightBaseAt != null;
        List<Part> left = parts(tenantId, unitId, leftSource,
                leftBaseAt != null ? leftBaseAt : latestBefore(tenantId, leftSource, before));
        List<Part> right = parts(tenantId, unitId, rightSource,
                rightBaseAt != null ? rightBaseAt : latestBefore(tenantId, rightSource, before));
        return new Breakdown(pair(left, right), exact);
    }

    /**
     * 여러 물건의 <b>이름과 든 품목 수</b>를 한 번에.
     *
     * <p>대조 결과 화면이 줄마다 물건을 다시 조회하면 줄 수만큼 질의가 늘어난다. 화면은 이미
     * 줄 목록을 갖고 있으므로 한 번에 받아 붙인다.
     */
    @Transactional(readOnly = true)
    public java.util.Map<UUID, Header> headers(UUID tenantId, java.util.Collection<UUID> unitIds,
                                               String leftSource, String rightSource) {
        if (unitIds == null || unitIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<UUID, Header> headers = new java.util.LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT u.id                                                        AS id,
                               u.name                                                      AS name,
                               count(m.id) FILTER (WHERE m.source = :leftSource)::int      AS left_parts,
                               count(m.id) FILTER (WHERE m.source = :rightSource)::int     AS right_parts
                          FROM reconcile_unit u
                          LEFT JOIN reconcile_unit_member m
                            ON m.unit_id = u.id AND m.confirmed_at IS NOT NULL
                         WHERE u.tenant_id = :tenantId
                           AND u.id IN (:unitIds)
                         GROUP BY u.id, u.name
                        """)
                .param("tenantId", tenantId)
                .param("unitIds", unitIds)
                .param("leftSource", leftSource)
                .param("rightSource", rightSource)
                .query((rs, rowNum) -> java.util.Map.entry(
                        rs.getObject("id", UUID.class),
                        new Header(rs.getString("name"), rs.getInt("left_parts"),
                                rs.getInt("right_parts"))))
                .list()
                .forEach(entry -> headers.put(entry.getKey(), entry.getValue()));
        return headers;
    }

    /** 물건 한 줄의 머리 정보 — 이름과 든 품목 수. */
    public record Header(String name, int leftParts, int rightParts) {
    }

    /** 이 물건에 든 품목들 — 그 시각의 수량과 함께. */
    private List<Part> parts(UUID tenantId, UUID unitId, String source, Instant baseAt) {
        if (baseAt == null) {
            return List.of();
        }
        return jdbcClient.sql("""
                        SELECT m.item_ref                          AS item_ref,
                               m.factor                            AS factor,
                               coalesce(max(s.raw_item_name), '')  AS raw_name,
                               sum(s.base_quantity)                AS qty
                          FROM reconcile_unit_member m
                          LEFT JOIN std_stock_snapshot s
                            ON s.tenant_id = m.tenant_id
                           AND s.source    = m.source
                           AND s.item_ref  = m.item_ref
                           AND s.base_at   = :baseAt
                         WHERE m.tenant_id     = :tenantId
                           AND m.unit_id       = :unitId
                           AND m.source        = :source
                           AND m.confirmed_at IS NOT NULL
                         GROUP BY m.item_ref, m.factor
                         ORDER BY m.item_ref
                        """)
                .param("tenantId", tenantId)
                .param("unitId", unitId)
                .param("source", source)
                .param("baseAt", baseAt.atOffset(java.time.ZoneOffset.UTC))
                .query((rs, rowNum) -> new Part(
                        source,
                        rs.getString("item_ref"),
                        rs.getString("raw_name"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("factor")))
                .list();
    }

    /** 그 시각 이전의 가장 최근 스냅샷. 옛 회차를 되짚을 때만 쓴다. */
    private Instant latestBefore(UUID tenantId, String source, Instant before) {
        if (before == null) {
            return null;
        }
        return jdbcClient.sql("""
                        SELECT max(base_at) FROM std_stock_snapshot
                         WHERE tenant_id = :tenantId AND source = :source
                           AND base_at <= :before
                        """)
                .param("tenantId", tenantId)
                .param("source", source)
                .param("before", before.atOffset(java.time.ZoneOffset.UTC))
                .query((rs, rowNum) -> {
                    var value = rs.getObject(1, java.time.OffsetDateTime.class);
                    return value == null ? null : value.toInstant();
                })
                .optional()
                .flatMap(Optional::ofNullable)
                .orElse(null);
    }

    /**
     * 좌·우를 짝지어 줄로 만든다.
     *
     * <p>점수가 높은 쌍부터 맺는다. 「클래식 850g (27.03.16)」 은 같은 날짜끼리 1.0 이 나오므로
     * 로트가 정확히 맞물리고, 그러고 남는 것이 실제로 어긋난 품목이다.
     */
    private List<Line> pair(List<Part> left, List<Part> right) {
        record Candidate(int leftAt, int rightAt, double score) {
        }

        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < left.size(); i++) {
            for (int j = 0; j < right.size(); j++) {
                candidates.add(new Candidate(i, j,
                        NameSimilarity.score(key(left.get(i)), key(right.get(j)))));
            }
        }
        // 점수가 같으면 앞자리부터 — 순서가 실행마다 달라지면 화면이 이유 없이 흔들린다.
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparingInt(Candidate::leftAt)
                .thenComparingInt(Candidate::rightAt));

        Set<Integer> usedLeft = new LinkedHashSet<>();
        Set<Integer> usedRight = new LinkedHashSet<>();
        List<Line> lines = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (usedLeft.contains(candidate.leftAt()) || usedRight.contains(candidate.rightAt())) {
                continue;
            }
            usedLeft.add(candidate.leftAt());
            usedRight.add(candidate.rightAt());
            lines.add(new Line(left.get(candidate.leftAt()), right.get(candidate.rightAt())));
        }
        for (int i = 0; i < left.size(); i++) {
            if (!usedLeft.contains(i)) {
                lines.add(new Line(left.get(i), null));
            }
        }
        for (int j = 0; j < right.size(); j++) {
            if (!usedRight.contains(j)) {
                lines.add(new Line(null, right.get(j)));
            }
        }
        // 짝 없는 줄을 아래로 몰고, 나머지는 이름순. 손댈 것이 바닥에 모인다.
        lines.sort(Comparator.comparing((Line line) -> line.paired() ? 0 : 1)
                .thenComparing(Line::label));
        return lines;
    }

    /** 견줄 때 쓰는 글자. <b>다듬지 않는다</b> — 다듬으면 로트 날짜가 떨어져 짝을 가릴 수 없다. */
    private String key(Part part) {
        return part.displayName().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 뜯어본 결과.
     *
     * @param exact 그 회차가 실제로 본 시각으로 계산했나. 거짓이면 되짚은 값이라 화면이 그
     *              사실을 말해야 한다
     */
    public record Breakdown(List<Line> lines, boolean exact) {

        /** 양쪽 다 있고 수량이 다른 줄 수. 「4건 중 3건이 어긋남」 을 말하는 데 쓴다. */
        public long differing() {
            return lines.stream().filter(Line::paired).filter(Line::hasDiff).count();
        }

        /** 한쪽에만 있는 줄 수. 대개 잘못 이어 둔 품목이다. */
        public long orphans() {
            return lines.stream().filter(line -> !line.paired()).count();
        }

        public BigDecimal leftTotal() {
            return lines.stream().map(Line::leftQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal rightTotal() {
            return lines.stream().map(Line::rightQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** 뜯어본 한 줄 — 좌·우 한 쌍, 또는 짝 없는 한쪽. */
    public record Line(Part left, Part right) {

        public boolean paired() {
            return left != null && right != null;
        }

        public String label() {
            return left != null ? left.displayName() : right.displayName();
        }

        public BigDecimal leftQuantity() {
            return left == null ? BigDecimal.ZERO : left.effectiveQuantity();
        }

        public BigDecimal rightQuantity() {
            return right == null ? BigDecimal.ZERO : right.effectiveQuantity();
        }

        public BigDecimal diff() {
            return leftQuantity().subtract(rightQuantity());
        }

        public boolean hasDiff() {
            return diff().signum() != 0;
        }

        public String leftText() {
            return left == null ? "—" : MatchBoard.amount(left.effectiveQuantity());
        }

        public String rightText() {
            return right == null ? "—" : MatchBoard.amount(right.effectiveQuantity());
        }

        public String diffText() {
            if (!paired()) {
                return "—";
            }
            BigDecimal value = diff();
            return value.signum() == 0 ? "—"
                    : (value.signum() > 0 ? "+" : "") + MatchBoard.amount(value);
        }

        /** 짝 없는 줄이 어느 쪽 것인가. 화면이 「이쪽에만 있음」 을 말하는 데 쓴다. */
        public String orphanSource() {
            return left != null ? left.source() : right.source();
        }
    }

    /** 물건에 든 품목 하나 — 그 시각의 수량과 함께. */
    public record Part(String source, String itemRef, String rawName, BigDecimal quantity,
                       BigDecimal factor) {

        public String displayName() {
            return rawName == null || rawName.isBlank() ? itemRef : rawName;
        }

        /** 그 시각에 이 품목이 재고에 없었다. 0 과 다르다 — 「없음」 이다. */
        public boolean inStock() {
            return quantity != null;
        }

        public BigDecimal effectiveQuantity() {
            if (quantity == null) {
                return BigDecimal.ZERO;
            }
            return factor == null ? quantity : quantity.multiply(factor);
        }

        public boolean hasFactor() {
            return factor != null && factor.compareTo(BigDecimal.ONE) != 0;
        }
    }
}
