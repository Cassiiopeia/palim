package kr.suhsaechan.palim.reconcile.match;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.reconcile.rule.NormalizationEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아직 정합 단위에 속하지 않은 품목을 모아 <b>같은 것일 법한 것끼리 묶는다.</b>
 *
 * <p>사람이 수백 품목을 하나씩 훑으며 짝을 찾는 일을 줄인다. 다만 <b>묶어서 보여줄 뿐 확정하지
 * 않는다</b> — 정규화 규칙이 틀리면 엉뚱한 품목이 한 묶음에 들어오는데, 그것을 자동으로 확정하면
 * 잘못 합쳐진 재고를 두고 "맞는다"고 보고하게 된다.
 *
 * <p>양쪽에서 하나씩 온 묶음이 가장 쓸모 있다. 한쪽에만 있는 것은 짝이 없다는 뜻이라 사람이
 * 새 단위를 만들어야 한다.
 */
@Component
@RequiredArgsConstructor
public class MatchCandidateFinder {

    private final JdbcClient jdbcClient;
    private final NormalizationEngine normalizer;

    /**
     * 두 원천의 미연결 품목을 정규화해 묶는다.
     *
     * <p><b>원천마다 자기 기준 시각을 쓴다.</b> 예전에는 왼쪽 원천의 시각 하나로 양쪽을
     * 훑었다. 두 원천이 같은 굵기로 담을 때는 우연히 맞지만, 한쪽을 촘촘하게 담도록 바꾸는
     * 순간 오른쪽 조회가 <b>0건</b>이 되고 후보가 절반만 나온다 — 화면은 「이을 만한 것이
     * 없습니다」 라고만 말하므로 사람은 그것이 잘못된 결과인 줄 모른다.
     *
     * @return 정규화 이름이 같은 것끼리 묶은 후보. 양쪽이 섞인 묶음이 앞에 온다
     */
    @Transactional(readOnly = true)
    public List<MatchCandidate> suggest(UUID tenantId, String leftSource, String rightSource) {
        List<SourceItem> items = new ArrayList<>();
        items.addAll(unlinkedItems(tenantId, leftSource));
        items.addAll(unlinkedItems(tenantId, rightSource));

        Map<String, List<SourceItem>> grouped = new LinkedHashMap<>();
        for (SourceItem item : items) {
            String key = normalizer.normalize(
                    item.rawName().isBlank() ? item.itemRef() : item.rawName());
            if (key.isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }

        return grouped.entrySet().stream()
                .map(entry -> new MatchCandidate(entry.getKey(), entry.getValue()))
                // 양쪽이 섞인 묶음이 가장 쓸모 있다. 한쪽뿐이면 짝이 없다는 뜻이다.
                .sorted((a, b) -> Boolean.compare(b.hasBothSides(), a.hasBothSides()))
                .toList();
    }

    /**
     * 확정된 연결이 없는 품목들 — <b>그 원천의 가장 최근 것</b>에서.
     *
     * <p>제안만 있는 것도 «아직 연결되지 않은» 것으로 본다.
     *
     * <p>기준 시각을 인자로 받지 않고 SQL 안에서 그 원천의 최신값을 쓴다. 부르는 쪽이
     * 시각을 정하면 <b>한쪽 시각으로 양쪽을 훑는 실수</b>가 다시 생긴다.
     */
    private List<SourceItem> unlinkedItems(UUID tenantId, String source) {
        return jdbcClient.sql("""
                        SELECT s.item_ref AS item_ref,
                               max(coalesce(s.raw_item_name, '')) AS raw_name,
                               sum(s.base_quantity) AS qty
                          FROM std_stock_snapshot s
                          LEFT JOIN reconcile_unit_member m
                            ON m.tenant_id = s.tenant_id
                           AND m.source    = s.source
                           AND m.item_ref  = s.item_ref
                           AND m.confirmed_at IS NOT NULL
                         WHERE s.tenant_id = :tenantId
                           AND s.source    = :source
                           AND s.base_at   = (SELECT max(base_at) FROM std_stock_snapshot
                                               WHERE tenant_id = :tenantId AND source = :source)
                           AND m.id IS NULL
                         GROUP BY s.item_ref
                        """)
                .param("tenantId", tenantId)
                .param("source", source)
                .query((rs, rowNum) -> new SourceItem(
                        source,
                        rs.getString("item_ref"),
                        rs.getString("raw_name"),
                        rs.getBigDecimal("qty")))
                .list();
    }

    /**
     * 같은 것일 법한 품목 묶음.
     *
     * @param normalizedName 다듬은 뒤의 이름. 이것이 같아서 묶였다
     * @param items          그 묶음에 든 원천 품목들
     */
    public record MatchCandidate(String normalizedName, List<SourceItem> items) {

        /** 양쪽 원천에서 하나씩 왔나. 그런 묶음이 바로 이어 붙일 수 있는 짝이다. */
        public boolean hasBothSides() {
            return items.stream().map(SourceItem::source).distinct().count() > 1;
        }
    }

    /**
     * 원천 품목 하나.
     *
     * @param rawName 원본 품명. 코드만 보여주면 사람이 무엇인지 알아볼 수 없다
     */
    public record SourceItem(String source, String itemRef, String rawName, BigDecimal quantity) {
    }
}
