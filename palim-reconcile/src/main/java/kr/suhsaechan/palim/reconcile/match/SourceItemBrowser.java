package kr.suhsaechan.palim.reconcile.match;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 한 원천에 담긴 품목을 <b>전부</b> 훑는다.
 *
 * <p>{@link MatchCandidateFinder} 와 하는 일이 다르다. 저쪽은 「이름이 닮은 것끼리 묶어
 * 보여주는」 것이고, 이것은 <b>「그 원천에 무엇이 있는지 다 보여주는」</b> 것이다.
 *
 * <p>이 조회가 없어서 <b>이름이 다른 품목은 이을 방법이 아예 없었다.</b> 자동 후보에 안 뜨면
 * 화면 어디에도 그 품목이 나타나지 않으므로, 사람이 「저건 내가 알아, 저 둘이 같은 거야」 라고
 * 알고 있어도 손을 댈 자리가 없다. 막다른 길이었다.
 *
 * <p>품목이 수백 개인 곳에서도 성립해야 하므로 검색·쪽 나누기를 조회가 맡는다. 다 읽어 와서
 * 화면이 자르는 방식은 품목이 늘면 그대로 무너진다.
 */
@Component
@RequiredArgsConstructor
public class SourceItemBrowser {

    /** 한 쪽에 보여줄 최대 행. 사람이 눈으로 훑을 수 있는 선이다. */
    public static final int PAGE_SIZE = 50;

    private final JdbcClient jdbcClient;

    /**
     * 그 원천의 최신 재고에 있는 품목들.
     *
     * @param keyword  품목코드·품명에 이 글자가 들어간 것만. 비면 전부
     * @param linkState 어떤 상태의 것을 볼지
     * @param page     0부터
     */
    @Transactional(readOnly = true)
    public List<BrowsedItem> browse(UUID tenantId, String source, String keyword,
                                    LinkState linkState, int page) {
        // 상태 조건만 문자열로 조립한다. 값은 전부 바인딩이고, 이 문자열은 enum 에서만 나온다.
        String stateCondition = switch (linkState) {
            case UNLINKED -> "AND m.id IS NULL";
            case LINKED -> "AND m.id IS NOT NULL";
            case ALL -> "";
        };

        return jdbcClient.sql("""
                        SELECT s.item_ref                          AS item_ref,
                               max(coalesce(s.raw_item_name, ''))  AS raw_name,
                               sum(s.base_quantity)                AS qty,
                               max(coalesce(s.base_unit, ''))      AS base_unit,
                               max(coalesce(u.name, ''))           AS unit_name,
                               max(coalesce(u.code, ''))           AS unit_code
                          FROM std_stock_snapshot s
                          LEFT JOIN reconcile_unit_member m
                            ON m.tenant_id = s.tenant_id
                           AND m.source    = s.source
                           AND m.item_ref  = s.item_ref
                          LEFT JOIN reconcile_unit u ON u.id = m.unit_id
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.base_at   = (SELECT max(base_at) FROM std_stock_snapshot
                                               WHERE tenant_id = :tenantId AND source = :source)
                           AND (:keyword = ''
                                OR s.item_ref ILIKE :like
                                OR coalesce(s.raw_item_name, '') ILIKE :like)
                        %s
                         GROUP BY s.item_ref
                         ORDER BY s.item_ref
                         LIMIT :limit OFFSET :offset
                        """.formatted(stateCondition))
                .param("tenantId", tenantId)
                .param("source", source)
                .param("keyword", keyword == null ? "" : keyword.trim())
                .param("like", "%" + (keyword == null ? "" : keyword.trim()) + "%")
                .param("limit", PAGE_SIZE)
                .param("offset", Math.max(0, page) * PAGE_SIZE)
                .query((rs, rowNum) -> new BrowsedItem(
                        source,
                        rs.getString("item_ref"),
                        rs.getString("raw_name"),
                        rs.getBigDecimal("qty"),
                        rs.getString("base_unit"),
                        rs.getString("unit_code"),
                        rs.getString("unit_name")))
                .list();
    }

    /** 같은 조건의 전체 건수. 쪽 나누기와 「N건 중 M건 보는 중」 에 쓴다. */
    @Transactional(readOnly = true)
    public int count(UUID tenantId, String source, String keyword, LinkState linkState) {
        String stateCondition = switch (linkState) {
            case UNLINKED -> "AND m.id IS NULL";
            case LINKED -> "AND m.id IS NOT NULL";
            case ALL -> "";
        };

        // PostgreSQL 의 count 는 bigint 다. int 로 받으려면 캐스팅한다.
        return jdbcClient.sql("""
                        SELECT count(DISTINCT s.item_ref)::int
                          FROM std_stock_snapshot s
                          LEFT JOIN reconcile_unit_member m
                            ON m.tenant_id = s.tenant_id
                           AND m.source    = s.source
                           AND m.item_ref  = s.item_ref
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.base_at   = (SELECT max(base_at) FROM std_stock_snapshot
                                               WHERE tenant_id = :tenantId AND source = :source)
                           AND (:keyword = ''
                                OR s.item_ref ILIKE :like
                                OR coalesce(s.raw_item_name, '') ILIKE :like)
                        %s
                        """.formatted(stateCondition))
                .param("tenantId", tenantId)
                .param("source", source)
                .param("keyword", keyword == null ? "" : keyword.trim())
                .param("like", "%" + (keyword == null ? "" : keyword.trim()) + "%")
                .query(Integer.class)
                .single();
    }

    /**
     * 담은 품목 하나를 <b>담긴 자료에서 다시</b> 확인한다.
     *
     * <p>사람이 보낸 값을 그대로 믿지 않는다. 화면에 그리는 품명·수량은 <b>지금 담겨 있는
     * 값</b>이어야 한다 — 그래야 「내가 무엇을 잇고 있는지」 가 사실과 어긋나지 않는다.
     *
     * @return 그 원천의 최신 재고에 없으면 빈 값
     */
    @Transactional(readOnly = true)
    public java.util.Optional<BrowsedItem> find(UUID tenantId, String source, String itemRef) {
        return jdbcClient.sql("""
                        SELECT s.item_ref                          AS item_ref,
                               max(coalesce(s.raw_item_name, ''))  AS raw_name,
                               sum(s.base_quantity)                AS qty,
                               max(coalesce(s.base_unit, ''))      AS base_unit,
                               max(coalesce(u.name, ''))           AS unit_name,
                               max(coalesce(u.code, ''))           AS unit_code
                          FROM std_stock_snapshot s
                          LEFT JOIN reconcile_unit_member m
                            ON m.tenant_id = s.tenant_id
                           AND m.source    = s.source
                           AND m.item_ref  = s.item_ref
                          LEFT JOIN reconcile_unit u ON u.id = m.unit_id
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.item_ref  = :itemRef
                           AND s.base_at   = (SELECT max(base_at) FROM std_stock_snapshot
                                               WHERE tenant_id = :tenantId AND source = :source)
                         GROUP BY s.item_ref
                        """)
                .param("tenantId", tenantId)
                .param("source", source)
                .param("itemRef", itemRef)
                .query((rs, rowNum) -> new BrowsedItem(
                        source,
                        rs.getString("item_ref"),
                        rs.getString("raw_name"),
                        rs.getBigDecimal("qty"),
                        rs.getString("base_unit"),
                        rs.getString("unit_code"),
                        rs.getString("unit_name")))
                .optional();
    }

    /** 어떤 상태의 품목을 볼지. SQL 조각이 이 enum 에서만 나오도록 묶어 둔다. */
    public enum LinkState {
        /** 아직 어느 품목에도 안 이은 것. 기본값 — 할 일이 남은 것들이다. */
        UNLINKED("아직 안 이음"),
        /** 이미 이어 둔 것. 병합하거나 잘못 이은 것을 찾을 때 본다. */
        LINKED("이어 둔 것"),
        ALL("전부");

        private final String label;

        LinkState(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /**
     * 목록에 보이는 품목 하나.
     *
     * @param rawName  원본 품명. 코드만으로는 사람이 무엇인지 알아볼 수 없다
     * @param unitCode 이미 이어 둔 품목의 코드. 안 이었으면 빈 문자열
     * @param unitName 이미 이어 둔 품목의 이름. 안 이었으면 빈 문자열
     */
    public record BrowsedItem(String source, String itemRef, String rawName, BigDecimal quantity,
                              String baseUnit, String unitCode, String unitName) {

        /** 이미 어느 품목에 속해 있나. 담으면 «병합» 이 된다. */
        public boolean linked() {
            return unitCode != null && !unitCode.isBlank();
        }

        /** 사람에게 보여줄 이름. 품명이 없으면 코드라도 보여준다 — 빈 칸보다 낫다. */
        public String displayName() {
            return rawName == null || rawName.isBlank() ? itemRef : rawName;
        }
    }
}
