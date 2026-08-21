package kr.suhsaechan.palim.reconcile.match;

import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.reconcile.define.Pairing;
import kr.suhsaechan.palim.reconcile.filter.FilterSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이미 만들어 둔 묶음의 이름을 <b>담긴 품명으로 다시 짓는다.</b>
 *
 * <p>이름 규칙을 고쳐도 <b>이미 만들어진 묶음은 옛 이름을 그대로 달고 있다.</b> 지금 열세 개
 * 중 여섯 개가 「클래식 850g (27.03.16)」 처럼 로트 날짜가 박힌 이름인데, 그것을 하나씩 손으로
 * 고치라고 하면 아무도 안 고친다 — 그러면 규칙을 고친 의미가 없다.
 *
 * <p><b>사람이 직접 지은 이름은 건드리지 않는다.</b> 다시 짓기는 「담긴 품명에서 다시 뽑겠다」
 * 는 뜻이지 「내가 정한 것을 덮겠다」 가 아니다. 그래서 부를 때 어떤 묶음을 다시 지을지
 * 사람이 고른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnitNaming {

    private final JdbcClient jdbcClient;

    /**
     * 이 묶음에 든 품목들의 품명으로 이름을 지어 본다.
     *
     * <p>지금 담긴 최신 재고의 품명을 쓴다 — 이름은 사람이 보는 값이므로 「그때 그랬던 이름」
     * 보다 「지금 그런 이름」 이 맞다.
     *
     * @return 지을 이름. 재료가 없으면 빈 문자열
     */
    @Transactional(readOnly = true)
    public String suggest(UUID tenantId, UUID unitId, Pairing pairing) {
        return CommonName.of(
                names(tenantId, unitId, pairing.leftSource(), pairing.leftFilter()),
                names(tenantId, unitId, pairing.rightSource(), pairing.rightFilter()));
    }

    /**
     * 다시 지을 만한 묶음들.
     *
     * <p><b>고치려는 것은 하나뿐이다</b> — 여러 품목을 묶었는데 <b>그중 하나의 이름이 묶음
     * 전체를 대표</b>하게 된 것. 「클래식 850g (27.03.16)」 은 로트 넷을 묶은 이름인데 첫 로트의
     * 날짜를 달고 있다.
     *
     * <p>그래서 <b>한쪽에 품목이 둘 이상일 때만</b> 제안한다. 하나뿐인 묶음은 애초에 그 문제가
     * 없다 — 그런데도 제안하면 「초콜렛 프로틴바」 를 「초콜릿 프로틴바 70g_26.12.12」 로
     * 바꾸라고 권하게 된다. <b>지금 이름이 더 나은데 나쁜 쪽으로 끌고 가는 제안</b>이고,
     * 그런 제안을 한 번 보면 이 목록 전체를 안 믿게 된다.
     *
     * <p>제안이 <b>지금 이름보다 길면</b> 그것도 뺀다. 다시 짓기는 군더더기를 떼는 일이지
     * 붙이는 일이 아니다.
     */
    @Transactional(readOnly = true)
    public List<Suggestion> suggestions(UUID tenantId, Pairing pairing) {
        List<Suggestion> found = new ArrayList<>();
        for (UnitRow unit : activeUnits(tenantId)) {
            List<String> left = names(tenantId, unit.id(), pairing.leftSource(), pairing.leftFilter());
            List<String> right = names(tenantId, unit.id(), pairing.rightSource(), pairing.rightFilter());
            if (left.size() < 2 && right.size() < 2) {
                continue;
            }
            String suggested = CommonName.of(left, right);
            if (suggested.isBlank() || suggested.equals(unit.name())
                    || suggested.length() >= unit.name().length()) {
                continue;
            }
            found.add(new Suggestion(unit.id(), unit.code(), unit.name(), suggested));
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

    /**
     * 이 묶음에 든 그 원천 품목들의 품명 — 지금 담긴 최신 재고에서.
     *
     * <p><b>창고 범위 안에서만 본다.</b> 정의가 안 보기로 한 창고에만 있는 품목의 이름이
     * 재료에 섞이면 두 가지가 어긋난다 — (가) 공통 부분이 실제보다 짧게 깎이고,
     * (나) 「한쪽에 둘 이상일 때만 제안한다」 는 판정이 부풀어, 범위 안에서는 1↔1 인 묶음까지
     * 다시 짓기 후보로 올라온다. 이 클래스가 스스로 경계한 「나쁜 쪽으로 끌고 가는 제안」 이다.
     *
     * <p>기준 시각을 고르는 서브쿼리에는 창고를 걸지 <b>않는다.</b> 시각은 「언제」 이고 창고는
     * 「얼마」 다 — 좁히면 그 창고에 자료가 없는 회차를 건너뛰어 더 옛 시각을 고르게 된다.
     */
    private List<String> names(UUID tenantId, UUID unitId, String source, FilterSpec filter) {
        FilterSpec.Compiled where = filter.compile("s", FilterSpec.PREFIX, Instant.now());
        return jdbcClient.sql("""
                        SELECT coalesce(max(s.raw_item_name), '') AS raw_name
                          FROM reconcile_unit_member m
                          LEFT JOIN std_stock_snapshot s
                            ON s.tenant_id = m.tenant_id
                           AND s.source    = m.source
                           AND s.item_ref  = m.item_ref
                           AND s.base_at   = (SELECT max(x.base_at) FROM std_stock_snapshot x
                                               WHERE x.tenant_id = m.tenant_id
                                                 AND x.source    = m.source)%s
                         WHERE m.tenant_id     = :tenantId
                           AND m.unit_id       = :unitId
                           AND m.source        = :source
                           -- 견주는 쪽(합계·뜯어보기)은 확정된 연결만 본다. 이름만 미확정까지
                           -- 세면 «묶음에 든 품목 수» 가 두 벌이 되어, 「한쪽에 둘 이상일 때만
                           -- 제안한다」 는 판정이 실제와 어긋난다.
                           AND m.confirmed_at IS NOT NULL
                         GROUP BY m.item_ref
                         ORDER BY m.item_ref
                        """.formatted(where.sql()))
                .param("tenantId", tenantId)
                .param("unitId", unitId)
                .param("source", source)
                .params(where.params())
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
