package kr.suhsaechan.palim.reconcile.match;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이미 만들어 둔 물건의 이름을 <b>담긴 품명으로 다시 짓는다.</b>
 *
 * <p>이름 규칙을 고쳐도 <b>이미 만들어진 물건은 옛 이름을 그대로 달고 있다.</b> 지금 열세 개
 * 중 여섯 개가 「클래식 850g (27.03.16)」 처럼 로트 날짜가 박힌 이름인데, 그것을 하나씩 손으로
 * 고치라고 하면 아무도 안 고친다 — 그러면 규칙을 고친 의미가 없다.
 *
 * <p><b>사람이 직접 지은 이름은 건드리지 않는다.</b> 다시 짓기는 「담긴 품명에서 다시 뽑겠다」
 * 는 뜻이지 「내가 정한 것을 덮겠다」 가 아니다. 그래서 부를 때 어떤 물건을 다시 지을지
 * 사람이 고른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnitNaming {

    private final JdbcClient jdbcClient;

    /**
     * 이 물건에 든 품목들의 품명으로 이름을 지어 본다.
     *
     * <p>지금 담긴 최신 재고의 품명을 쓴다 — 이름은 사람이 보는 값이므로 「그때 그랬던 이름」
     * 보다 「지금 그런 이름」 이 맞다.
     *
     * @return 지을 이름. 재료가 없으면 빈 문자열
     */
    @Transactional(readOnly = true)
    public String suggest(UUID tenantId, UUID unitId, String leftSource, String rightSource) {
        return CommonName.of(names(tenantId, unitId, leftSource), names(tenantId, unitId, rightSource));
    }

    /** 지금 이름이 담긴 품명과 어긋나는 물건들. 다시 지을 후보를 사람에게 보여줄 때 쓴다. */
    @Transactional(readOnly = true)
    public List<Suggestion> suggestions(UUID tenantId, String leftSource, String rightSource) {
        List<Suggestion> found = new ArrayList<>();
        for (UnitRow unit : activeUnits(tenantId)) {
            String suggested = suggest(tenantId, unit.id(), leftSource, rightSource);
            if (!suggested.isBlank() && !suggested.equals(unit.name())) {
                found.add(new Suggestion(unit.id(), unit.code(), unit.name(), suggested));
            }
        }
        return found;
    }

    private List<UnitRow> activeUnits(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT id, code, name FROM reconcile_unit
                         WHERE tenant_id = :tenantId AND is_active
                         ORDER BY name
                        """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new UnitRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name")))
                .list();
    }

    /** 이 물건에 든 그 원천 품목들의 품명 — 지금 담긴 최신 재고에서. */
    private List<String> names(UUID tenantId, UUID unitId, String source) {
        return jdbcClient.sql("""
                        SELECT coalesce(max(s.raw_item_name), '') AS raw_name
                          FROM reconcile_unit_member m
                          LEFT JOIN std_stock_snapshot s
                            ON s.tenant_id = m.tenant_id
                           AND s.source    = m.source
                           AND s.item_ref  = m.item_ref
                           AND s.base_at   = (SELECT max(x.base_at) FROM std_stock_snapshot x
                                               WHERE x.tenant_id = m.tenant_id
                                                 AND x.source    = m.source)
                         WHERE m.tenant_id = :tenantId
                           AND m.unit_id   = :unitId
                           AND m.source    = :source
                         GROUP BY m.item_ref
                         ORDER BY m.item_ref
                        """)
                .param("tenantId", tenantId)
                .param("unitId", unitId)
                .param("source", source)
                .query(String.class)
                .list()
                .stream()
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    /** 다시 지을 후보 하나. */
    public record Suggestion(UUID unitId, String code, String currentName, String suggestedName) {
    }

    private record UnitRow(UUID id, String code, String name) {
    }
}
