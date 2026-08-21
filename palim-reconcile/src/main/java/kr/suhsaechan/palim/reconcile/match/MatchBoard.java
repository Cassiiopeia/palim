package kr.suhsaechan.palim.reconcile.match;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.suhsaechan.palim.reconcile.define.Pairing;
import kr.suhsaechan.palim.reconcile.filter.FilterSpec;
import kr.suhsaechan.palim.reconcile.rule.NormalizationEngine;
import kr.suhsaechan.palim.reconcile.rule.RegexGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 두 원천의 품목을 <b>한 표로 나란히 놓는다.</b>
 *
 * <p>예전에는 화면이 셋으로 쪼개져 있었다 — 「묶을 만한 것」(이름이 같은 것끼리),
 * 「직접 골라서 묶기」(좌·우 목록 따로), 「정해 둔 품목」(코드와 이름만). 그래서
 * <b>무엇과 무엇이 같은지를 한눈에 볼 수 없었고</b>, 잇기 전에 양쪽 수량을 견줄 수도 없었다.
 * 잇고 나면 무슨 일이 일어났는지도 안 보였다.
 *
 * <p>여기서는 한 줄이 <b>묶음 하나</b>다. 왼쪽 칸에 이쪽 시스템의 품목, 오른쪽 칸에 저쪽
 * 시스템의 품목, 그 옆에 수량 차이. 그러면 잇기 전에 「이게 맞나」 를 그 줄에서 판단할 수 있고,
 * 잇고 나서도 같은 줄이 남아 무엇을 이었는지 보인다.
 *
 * <p>줄이 만들어지는 근거는 셋뿐이고 순서대로 본다.
 * <ol>
 *   <li>이미 묶어 둔 것이면 <b>그 묶음 단위로</b> 한 줄 — 사람이 정한 것이 규칙보다 앞선다</li>
 *   <li>짝 없음으로 표시해 둔 것이면 <b>품목 하나가 한 줄</b> — 다른 것과 섞이면 안 된다</li>
 *   <li>나머지는 <b>다듬은 이름이 같은 것끼리</b> 한 줄</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class MatchBoard {

    /** 한 쪽에 보여줄 줄 수. 표가 두 칸씩이라 예전 목록보다 줄당 높이가 크다. */
    public static final int PAGE_SIZE = 25;

    /**
     * 이름 다듬기에 걸어 두는 제한 시간.
     *
     * <p>여기서 도는 것은 <b>사람이 넣은 정규식</b>이다. 미리보기를 통과했다고 안전한 것이 아니다 —
     * 표본 열두 개로는 되돌아가는 패턴이 드러나지 않는다. 그런 규칙이 하나 저장되면 이 화면을 열
     * 때마다 요청 스레드가 영영 풀려나지 않고, 몇 번이면 서버 전체가 응답을 멈춘다.
     */
    private static final Duration NORMALIZE_TIMEOUT = Duration.ofSeconds(10);

    private final JdbcClient jdbcClient;
    private final NormalizationEngine normalizer;
    private final UnpairedItemRepository unpaired;

    /**
     * 보드를 만든다.
     *
     * @param tab     어느 갈래를 볼지
     * @param keyword 품목코드·품명·묶음 이름에 이 글자가 든 줄만. 비면 전부
     * @param page    0부터
     */
    @Transactional(readOnly = true)
    public Board load(UUID tenantId, Pairing pairing, Tab tab, String keyword, int page) {
        List<Row> all = allRows(tenantId, pairing);
        Counts counts = Counts.of(all);

        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<Row> filtered = all.stream()
                .filter(row -> tab.accepts(row.kind()))
                .filter(row -> needle.isEmpty() || row.matches(needle))
                .sorted(Comparator.comparing(Row::displayName)
                        .thenComparing(Row::key))
                .toList();

        int from = Math.min(Math.max(0, page) * PAGE_SIZE, filtered.size());
        int to = Math.min(from + PAGE_SIZE, filtered.size());
        return new Board(pairing.leftSource(), pairing.rightSource(),
                filtered.subList(from, to), counts,
                Math.max(0, page), filtered.size(), PAGE_SIZE);
    }

    /**
     * 줄 하나를 열쇠로 다시 찾는다.
     *
     * <p>화면이 보낸 품목 목록을 그대로 믿지 않고 <b>서버가 그 줄을 다시 계산한다.</b> 화면을
     * 띄운 뒤 다른 사람이 그 품목을 이어 버렸을 수도 있고, 주소를 손으로 고쳐 남의 품목을
     * 끼워 넣을 수도 있다.
     */
    @Transactional(readOnly = true)
    public Optional<Row> findRow(UUID tenantId, Pairing pairing, String key) {
        return allRows(tenantId, pairing).stream()
                .filter(row -> row.key().equals(key))
                .findFirst();
    }

    /** 품목 하나를 지금 담긴 재고에서 찾는다. 편집 화면이 품명·수량을 붙이는 데 쓴다. */
    @Transactional(readOnly = true)
    public Optional<Item> findItem(UUID tenantId, String source, String itemRef,
                                   FilterSpec filter) {
        // 이 조회는 「가장 최근 자료」 를 본다. 회차 기준 시각이 없으므로 상대 날짜는 「지금」 을
        // 기준으로 푼다 — 이 화면의 뜻과 맞는다.
        FilterSpec.Compiled where = filter.compile("s", FilterSpec.PREFIX, Instant.now());
        return jdbcClient.sql("""
                        SELECT s.item_ref                          AS item_ref,
                               max(coalesce(s.raw_item_name, ''))  AS raw_name,
                               sum(s.base_quantity)                AS qty
                          FROM std_stock_snapshot s
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.item_ref  = :itemRef
                           AND s.base_at   = (SELECT max(x.base_at) FROM std_stock_snapshot x
                                               WHERE x.tenant_id = :tenantId AND x.source = :source)%s
                         GROUP BY s.item_ref
                        """.formatted(where.sql()))
                .param("tenantId", tenantId)
                .param("source", source)
                .param("itemRef", itemRef)
                .params(where.params())
                .query((rs, rowNum) -> new Item(source, rs.getString("item_ref"),
                        rs.getString("raw_name"), rs.getBigDecimal("qty"),
                        BigDecimal.ONE, null, null, null, false))
                .optional();
    }

    /** 품목 하나가 든 줄. 짝 후보는 줄이 아니라 품목이라 이 길이 필요하다. */
    @Transactional(readOnly = true)
    public Optional<Row> findRowByItem(UUID tenantId, Pairing pairing, String token) {
        return allRows(tenantId, pairing).stream()
                .filter(row -> row.items().stream()
                        .anyMatch(item -> item.token().equals(token)))
                .findFirst();
    }

    /**
     * 줄 안에서 고를 <b>반대쪽 짝 후보</b>.
     *
     * <p>묶어 둔 것과 짝 없음으로 둔 것은 뺀다. 이 자리는 「짝을 찾는」 자리이므로 이미 자리를
     * 잡은 품목이 섞이면 고를 것이 늘기만 한다.
     *
     * <p>검색어가 없으면 <b>이름이 닮은 순서</b>로 준다. 자동 후보는 다듬은 이름이 «정확히»
     * 같아야 잡히므로 「초콜릿 프로틴바」 와 「초콜렛 프로틴바」 는 영영 못 만난다. 순서만
     * 바꾸는 것이지 <b>대신 정해 주지는 않는다</b> — 고르는 것은 사람이다.
     *
     * @param side      이쪽 원천에서만 고른다. {@code null} 이면 양쪽 다 — 이미 이어 둔 묶음에
     *                  품목을 «더 담을» 때는 어느 쪽에서 담을지 미리 정할 수 없다
     * @param reference 이 이름과 닮은 순서로 정렬한다
     */
    @Transactional(readOnly = true)
    public List<Item> mateCandidates(UUID tenantId, Pairing pairing,
                                     String side, String reference, String keyword, int limit) {
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        NormalizationEngine.Batch batch = normalizer.batch();
        String base = batch.normalize(reference);

        List<Item> free = allRows(tenantId, pairing).stream()
                .filter(row -> row.kind() == Kind.PAIRED
                        || row.kind() == Kind.LEFT_ONLY || row.kind() == Kind.RIGHT_ONLY)
                .flatMap(row -> (side == null ? row.items()
                        : side.equals(pairing.leftSource()) ? row.left() : row.right()).stream())
                .filter(item -> needle.isEmpty() || item.matches(needle))
                .toList();

        // 후보는 «자기 원천» 규칙으로 다듬어야 기준 이름과 같은 모양이 된다 — 원천별 규칙의
        // 목적이 바로 다른 표기를 같은 모양으로 만드는 것이다. 기준 이름은 어느 원천에서 왔는지
        // 알 수 없어 전체 규칙으로 다듬는다. 순서를 정하는 용도이므로 그 근사로 충분하다.
        Comparator<Item> order = needle.isEmpty()
                ? Comparator.comparingDouble((Item item) ->
                        -NameSimilarity.score(base,
                                batch.normalize(item.displayName(), item.source())))
                .thenComparing(Item::displayName)
                : Comparator.comparing(Item::displayName);

        return free.stream().sorted(order).limit(limit).toList();
    }

    // ── 계산 ────────────────────────────────────────────────────────────────

    private List<Row> allRows(UUID tenantId, Pairing pairing) {
        String leftSource = pairing.leftSource();
        String rightSource = pairing.rightSource();
        Map<String, Item> items = new LinkedHashMap<>();
        for (StockLine line : stockLines(tenantId, pairing)) {
            items.put(tokenOf(line.source(), line.itemRef()),
                    new Item(line.source(), line.itemRef(), line.rawName(), line.quantity(),
                            BigDecimal.ONE, null, null, null, false));
        }

        // 이어 둔 품목이 지금 담긴 재고에 «없을» 수 있다 — 원천이 품목코드를 바꾸거나 그날
        // 재고가 안 잡히면 그렇다. 줄에서 빼 버리면 끊을 자리도 함께 사라져 막다른 길이 된다.
        for (MemberLine line : memberLines(tenantId, leftSource, rightSource)) {
            items.compute(tokenOf(line.source(), line.itemRef()), (token, existing) ->
                    existing == null
                            ? new Item(line.source(), line.itemRef(), line.itemRef(), null,
                            line.factor(), line.memberId(), line.unitId(), null, line.confirmed())
                            : existing.linkedTo(line));
        }

        for (UnpairedItem mark : unpaired.findBySourceInOrderBySourceAscItemRefAsc(
                List.of(leftSource, rightSource))) {
            Item existing = items.get(tokenOf(mark.getSource(), mark.getItemRef()));
            if (existing != null) {
                items.put(tokenOf(mark.getSource(), mark.getItemRef()),
                        existing.markedUnpaired(mark.getReason()));
            }
        }

        NormalizationEngine.Batch batch = normalizer.batch();
        // 여기서 도는 것은 «사람이 넣은 정규식»이다. 미리보기를 통과했다고 안전한 것이 아니다 —
        // 표본 열두 개로는 되돌아가는 패턴이 드러나지 않는다. 그런 규칙 하나가 저장되면 이 화면을
        // 열 때마다 요청 스레드가 영영 풀려나지 않고, 몇 번이면 서버 전체가 멈춘다.
        // 제한 시간을 넘기면 화면은 오류로 열린다 — 그래야 규칙을 고치러 갈 수 있다.
        Map<String, List<Item>> grouped = RegexGuard.runWithTimeout(NORMALIZE_TIMEOUT, () -> {
            Map<String, List<Item>> byKey = new LinkedHashMap<>();
            for (Item item : items.values()) {
                byKey.computeIfAbsent(groupKeyOf(item, batch), key -> new ArrayList<>()).add(item);
            }
            return byKey;
        });

        Map<UUID, String> unitNames = unitNames(tenantId);
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<Item>> entry : grouped.entrySet()) {
            rows.add(rowOf(entry.getKey(), entry.getValue(), leftSource, unitNames));
        }
        return rows;
    }

    /**
     * 이 품목이 어느 줄에 들어가나.
     *
     * <p>사람이 정한 것(묶어 둔 것·짝 없음)이 규칙보다 <b>앞선다.</b> 반대로 하면 규칙 한 줄을
     * 고칠 때마다 이미 정해 둔 것이 흩어진다.
     */
    private String groupKeyOf(Item item, NormalizationEngine.Batch batch) {
        if (item.unitId() != null) {
            return "U:" + item.unitId();
        }
        if (item.unpairedReason() != null) {
            return "X:" + item.token();
        }
        // 그 원천에 걸어 둔 규칙으로 다듬는다. 원천을 안 넘기면 한쪽에만 걸어 둔 규칙이
        // 반대쪽 이름까지 바꿔, 화면에서 원천을 고른 것이 아무 일도 하지 않게 된다.
        String normalized = batch.normalize(
                item.rawName() == null || item.rawName().isBlank()
                        ? item.itemRef() : item.rawName(),
                item.source());
        // 다듬고 나니 빈 이름이면 규칙이 다 지워 버린 것이다. 그런 것끼리 한 줄로 뭉치면
        // 서로 아무 상관 없는 품목이 같은 묶음처럼 보인다 — 품목마다 따로 둔다.
        return normalized.isBlank() ? "N:" + item.token() : "N:" + normalized;
    }

    private Row rowOf(String key, List<Item> members, String leftSource,
                      Map<UUID, String> unitNames) {
        List<Item> left = members.stream().filter(i -> i.source().equals(leftSource)).toList();
        List<Item> right = members.stream().filter(i -> !i.source().equals(leftSource)).toList();

        UUID unitId = members.getFirst().unitId();
        if (unitId != null) {
            return new Row(key, Kind.LINKED, unitId,
                    unitNames.getOrDefault(unitId, "이름 없음"),
                    members.stream().allMatch(Item::confirmed), left, right);
        }
        if (members.getFirst().unpairedReason() != null) {
            return new Row(key, Kind.SET_ASIDE, null, null, false, left, right);
        }
        Kind kind = !left.isEmpty() && !right.isEmpty() ? Kind.PAIRED
                : left.isEmpty() ? Kind.RIGHT_ONLY : Kind.LEFT_ONLY;
        return new Row(key, kind, null, null, false, left, right);
    }

    private Map<UUID, String> unitNames(UUID tenantId) {
        Map<UUID, String> names = new LinkedHashMap<>();
        // 접어 둔 묶음도 이름은 필요하다. 멤버가 남아 있으면 줄에 뜨고, 그 줄에서 끊어야 한다.
        jdbcClient.sql("SELECT id, name FROM reconcile_unit WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> Map.entry(rs.getObject("id", UUID.class),
                        rs.getString("name")))
                .list()
                .forEach(entry -> names.put(entry.getKey(), entry.getValue()));
        return names;
    }

    /**
     * 두 원천의 <b>가장 최근</b> 재고에 있는 품목들.
     *
     * <p>기준 시각을 원천마다 따로 잡는다. 한쪽 시각으로 양쪽을 훑으면, 한쪽을 더 촘촘하게
     * 담도록 바꾸는 순간 반대쪽이 <b>0건</b>이 되면서 화면은 「짝이 없습니다」 라고만 말한다.
     */
    private List<StockLine> stockLines(UUID tenantId, Pairing pairing) {
        // 원천마다 볼 조건이 다르다. 한 이름으로 걸면 뒤엣값이 앞을 덮어써 양쪽이 같은 조건으로
        // 걸리므로, 좌·우를 다른 접두어로 바인딩한다.
        Instant asOf = Instant.now();
        FilterSpec.Compiled left = pairing.leftFilter().compile("s", "lf", asOf);
        FilterSpec.Compiled right = pairing.rightFilter().compile("s", "rf", asOf);

        return jdbcClient.sql("""
                        SELECT s.source                            AS source,
                               s.item_ref                          AS item_ref,
                               max(coalesce(s.raw_item_name, ''))  AS raw_name,
                               sum(s.base_quantity)                AS qty
                          FROM std_stock_snapshot s
                         WHERE s.tenant_id = :tenantId
                           AND (    (s.source = :leftSource%s)
                                 OR (s.source = :rightSource%s) )
                           AND s.base_at   = (SELECT max(x.base_at) FROM std_stock_snapshot x
                                               WHERE x.tenant_id = s.tenant_id
                                                 AND x.source    = s.source)
                         GROUP BY s.source, s.item_ref
                        """.formatted(left.sql(), right.sql()))
                .param("tenantId", tenantId)
                .param("leftSource", pairing.leftSource())
                .param("rightSource", pairing.rightSource())
                .params(left.params())
                .params(right.params())
                .query((rs, rowNum) -> new StockLine(
                        rs.getString("source"), rs.getString("item_ref"),
                        rs.getString("raw_name"), rs.getBigDecimal("qty")))
                .list();
    }

    private List<MemberLine> memberLines(UUID tenantId, String leftSource, String rightSource) {
        return jdbcClient.sql("""
                        SELECT m.id        AS member_id,
                               m.unit_id   AS unit_id,
                               m.source    AS source,
                               m.item_ref  AS item_ref,
                               m.factor    AS factor,
                               (m.confirmed_at IS NOT NULL) AS confirmed
                          FROM reconcile_unit_member m
                         WHERE m.tenant_id = :tenantId
                           AND m.source   IN (:leftSource, :rightSource)
                        """)
                .param("tenantId", tenantId)
                .param("leftSource", leftSource)
                .param("rightSource", rightSource)
                .query((rs, rowNum) -> new MemberLine(
                        rs.getObject("member_id", UUID.class),
                        rs.getObject("unit_id", UUID.class),
                        rs.getString("source"), rs.getString("item_ref"),
                        rs.getBigDecimal("factor"), rs.getBoolean("confirmed")))
                .list();
    }

    /** 화면과 폼이 품목 하나를 가리키는 형태. 원천 이름에는 {@code |} 가 없다(연동 코드 규칙). */
    public static String tokenOf(String source, String itemRef) {
        return source + "|" + itemRef;
    }

    /**
     * 수량을 사람이 견줄 수 있게 적는다.
     *
     * <p>값을 바꾸지 않는다 — 자릿점을 찍고 <b>뜻 없는 뒷자리 0을 지울</b> 뿐이다.
     * 「9563.000 vs 9451.000」 을 눈으로 빼는 것과 「9,563 vs 9,451」 을 빼는 것은 다른 일이고,
     * 이 화면은 그 뺄셈을 하러 오는 화면이다.
     */
    public static String amount(BigDecimal value) {
        if (value == null) {
            // 값 없음은 하나로 말한다(11-UI-RULES E4).
            return "—";
        }
        BigDecimal trimmed = value.stripTrailingZeros();
        if (trimmed.scale() < 0) {
            trimmed = trimmed.setScale(0, RoundingMode.UNNECESSARY);
        }
        NumberFormat format = NumberFormat.getInstance(Locale.KOREA);
        format.setMaximumFractionDigits(Math.max(0, trimmed.scale()));
        format.setGroupingUsed(true);
        return format.format(trimmed);
    }

    private record StockLine(String source, String itemRef, String rawName, BigDecimal quantity) {
    }

    private record MemberLine(UUID memberId, UUID unitId, String source, String itemRef,
                              BigDecimal factor, boolean confirmed) {
    }

    // ── 보이는 것 ───────────────────────────────────────────────────────────

    /** 줄의 갈래. 탭이 이것으로 갈린다. */
    public enum Kind { PAIRED, LEFT_ONLY, RIGHT_ONLY, LINKED, SET_ASIDE }

    /** 어느 갈래를 볼지. SQL 이 아니라 계산 뒤에 거르므로 개수와 목록이 항상 맞는다. */
    public enum Tab {
        TODO("할 일", "이름이 닮은 짝과 아직 묶을 짝이 없는 것"),
        PAIRED("묶을 수 있는 것", "양쪽에 이름이 닮은 품목이 있습니다"),
        ONE_SIDED("묶을 짝이 없는 것", "한쪽에만 있습니다"),
        LINKED("묶어 둔 것", "이미 같은 묶음으로 정해 둔 것"),
        SET_ASIDE("짝 없음으로 둔 것", "짝이 없다고 사람이 정해 둔 것"),
        ALL("전부", "");

        private final String label;
        private final String hint;

        Tab(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }

        public String getLabel() {
            return label;
        }

        public String getHint() {
            return hint;
        }

        public boolean accepts(Kind kind) {
            return switch (this) {
                case ALL -> true;
                case TODO -> kind == Kind.PAIRED || kind == Kind.LEFT_ONLY
                        || kind == Kind.RIGHT_ONLY;
                case PAIRED -> kind == Kind.PAIRED;
                case ONE_SIDED -> kind == Kind.LEFT_ONLY || kind == Kind.RIGHT_ONLY;
                case LINKED -> kind == Kind.LINKED;
                case SET_ASIDE -> kind == Kind.SET_ASIDE;
            };
        }

        /** 주소를 손으로 고쳐 이상한 값이 와도 화면이 깨지지 않는다. */
        public static Tab of(String raw) {
            if (raw == null || raw.isBlank()) {
                return TODO;
            }
            try {
                return valueOf(raw);
            } catch (IllegalArgumentException e) {
                return TODO;
            }
        }
    }

    /**
     * 표 한 줄 — <b>묶음 하나</b>.
     *
     * @param unitId 이어 둔 줄일 때만 있다
     * @param left   왼쪽 시스템의 품목들. 로트가 갈려 여러 개일 수 있다
     */
    public record Row(String key, Kind kind, UUID unitId, String unitName, boolean confirmed,
                      List<Item> left, List<Item> right) {

        public BigDecimal leftTotal() {
            return total(left);
        }

        public String leftTotalText() {
            return left.isEmpty() ? "—" : amount(leftTotal());
        }

        public String rightTotalText() {
            return right.isEmpty() ? "—" : amount(rightTotal());
        }

        /** 부호를 붙여 어느 쪽이 많은지 한눈에 보이게 한다. */
        public String diffText() {
            BigDecimal value = diff();
            return (value.signum() > 0 ? "+" : "") + amount(value);
        }

        public BigDecimal rightTotal() {
            return total(right);
        }

        private BigDecimal total(List<Item> items) {
            return items.stream()
                    .map(Item::effectiveQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /**
         * 왼쪽에서 오른쪽을 뺀 값.
         *
         * <p>이 숫자가 <b>잇기 버튼 바로 옆에</b> 있어야 한다. 없으면 사람이 여러 줄을 눈으로
         * 더해야 하고, 결국 잇고 나서 대조를 돌려야 처음 보인다 — 그때는 이미 늦다.
         */
        public BigDecimal diff() {
            return leftTotal().subtract(rightTotal());
        }

        /** 양쪽에 다 있고 수량이 다른가. 한쪽만 있는 줄에서는 차이가 뜻이 없다. */
        public boolean hasDiff() {
            return bothSides() && diff().signum() != 0;
        }

        public boolean bothSides() {
            return !left.isEmpty() && !right.isEmpty();
        }

        /**
         * 차이가 큰 쪽 대비 몇 퍼센트인가. 소수 첫째 자리까지.
         *
         * <p>절대값만으로는 판단이 안 선다 — 9,563 중 112 와 5 중 112 는 전혀 다른 이야기다.
         */
        public BigDecimal diffPercent() {
            BigDecimal base = leftTotal().abs().max(rightTotal().abs());
            if (base.signum() == 0) {
                return BigDecimal.ZERO;
            }
            return diff().abs().multiply(BigDecimal.valueOf(100))
                    .divide(base, 1, RoundingMode.HALF_UP);
        }

        /**
         * 이 줄을 묶음으로 만들 때 <b>지어 줄 이름.</b>
         *
         * <p>첫 품목의 이름을 그대로 쓰면 로트 네 개를 묶은 묶음이 「클래식 850g (27.03.16)」 이
         * 된다 — 특정 로트 날짜가 전체를 대표하게 되고, 목록에서 그 로트 하나의 이야기로 읽힌다.
         * 그래서 여럿이면 공통 부분만 쓴다(07-DECISIONS 038).
         */
        public String suggestedName() {
            String suggested = CommonName.of(
                    left.stream().map(Item::displayName).toList(),
                    right.stream().map(Item::displayName).toList());
            return suggested.isBlank() ? displayName() : suggested;
        }

        /** 사람이 부르는 이름. 이어 둔 줄은 그 묶음의 이름, 아니면 첫 품목의 품명. */
        public String displayName() {
            if (unitName != null && !unitName.isBlank()) {
                return unitName;
            }
            return items().stream()
                    .map(Item::displayName)
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst()
                    .orElse("이름 없음");
        }

        public List<Item> items() {
            List<Item> all = new ArrayList<>(left);
            all.addAll(right);
            return all;
        }

        /** 이 줄에 <b>지금 담긴 재고에 없는</b> 품목이 섞여 있다. */
        public boolean hasMissing() {
            return items().stream().anyMatch(item -> item.quantity() == null);
        }

        public UnpairedItem.Reason unpairedReason() {
            return items().getFirst().unpairedReason();
        }

        boolean matches(String needle) {
            return displayName().toLowerCase(Locale.ROOT).contains(needle)
                    || items().stream().anyMatch(item -> item.matches(needle));
        }
    }

    /**
     * 줄 안의 품목 하나.
     *
     * @param quantity 지금 담긴 재고의 수량. <b>{@code null} 이면 담긴 재고에 없다</b>
     * @param factor   이 품목 하나가 묶음 몇 개인가. 이어 두지 않았으면 1
     * @param memberId 이어 둔 줄일 때만 있다. 끊기·계수 고치기가 이것을 가리킨다
     */
    public record Item(String source, String itemRef, String rawName, BigDecimal quantity,
                       BigDecimal factor, UUID memberId, UUID unitId,
                       UnpairedItem.Reason unpairedReason, boolean confirmed) {

        public String token() {
            return tokenOf(source, itemRef);
        }

        /** 품명이 없으면 코드라도 보여준다 — 빈 칸보다 낫다. */
        public String displayName() {
            return rawName == null || rawName.isBlank() ? itemRef : rawName;
        }

        /** 대조에 더해질 수량. 계수를 안 건드렸으면 담긴 수량 그대로다. */
        public BigDecimal effectiveQuantity() {
            if (quantity == null) {
                return BigDecimal.ZERO;
            }
            return factor == null ? quantity : quantity.multiply(factor);
        }

        public boolean inStock() {
            return quantity != null;
        }

        public String quantityText() {
            return amount(quantity);
        }

        public String effectiveQuantityText() {
            return quantity == null ? "—" : amount(effectiveQuantity());
        }

        /**
         * 이 품목을 저쪽과 묶으면 차이가 얼마가 되나.
         *
         * <p>짝 후보를 고르는 <b>진짜 기준</b>이다. 수량만 늘어놓으면 사람이 머리로 빼야 하고,
         * 후보가 열 개면 열 번 빼다가 결국 대충 고른다.
         */
        public String diffTextAgainst(BigDecimal other) {
            BigDecimal value = (other == null ? BigDecimal.ZERO : other)
                    .subtract(effectiveQuantity());
            return value.signum() == 0 ? "맞음"
                    : (value.signum() > 0 ? "+" : "") + amount(value);
        }

        /** 차이가 있나. 없는 줄에는 색을 켜지 않는다. */
        public boolean differsFrom(BigDecimal other) {
            return (other == null ? BigDecimal.ZERO : other)
                    .compareTo(effectiveQuantity()) != 0;
        }

        /** 계수가 1이 아니다. 그럴 때만 화면이 계수를 말한다 — 늘 보이면 잡음이다. */
        public boolean hasFactor() {
            return factor != null && factor.compareTo(BigDecimal.ONE) != 0;
        }

        Item linkedTo(MemberLine line) {
            return new Item(source, itemRef, rawName, quantity, line.factor(),
                    line.memberId(), line.unitId(), unpairedReason, line.confirmed());
        }

        Item markedUnpaired(UnpairedItem.Reason reason) {
            return new Item(source, itemRef, rawName, quantity, factor, memberId, unitId,
                    reason, confirmed);
        }

        boolean matches(String needle) {
            return itemRef.toLowerCase(Locale.ROOT).contains(needle)
                    || displayName().toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    /** 갈래별 개수. <b>거르기 전</b> 전체를 센다 — 탭 숫자가 지금 보는 목록에 따라 변하면 안 된다. */
    public record Counts(int paired, int oneSided, int linked, int setAside) {

        static Counts of(List<Row> rows) {
            int paired = 0;
            int oneSided = 0;
            int linked = 0;
            int setAside = 0;
            for (Row row : rows) {
                switch (row.kind()) {
                    case PAIRED -> paired++;
                    case LEFT_ONLY, RIGHT_ONLY -> oneSided++;
                    case LINKED -> linked++;
                    case SET_ASIDE -> setAside++;
                }
            }
            return new Counts(paired, oneSided, linked, setAside);
        }

        /** 아직 사람이 손대지 않은 것. 이것이 0이 되면 대조를 돌릴 준비가 끝난 것이다. */
        public int todo() {
            return paired + oneSided;
        }

        public boolean done() {
            return todo() == 0 && linked > 0;
        }
    }

    /** 화면에 넘길 한 판. */
    public record Board(String leftSource, String rightSource, List<Row> rows, Counts counts,
                        int page, int totalRows, int pageSize) {

        public boolean hasPrev() {
            return page > 0;
        }

        public boolean hasNext() {
            return (page + 1) * pageSize < totalRows;
        }

        public int pageCount() {
            return Math.max(1, (totalRows + pageSize - 1) / pageSize);
        }
    }
}
