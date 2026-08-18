package kr.suhsaechan.palim.reconcile.rule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 규칙이 <b>실제로 무슨 일을 하고 있는지</b> 숫자로 말한다.
 *
 * <p>지금까지 규칙 화면이 말해 주는 것은 규칙의 이름과 정규식뿐이었다. 그래서 규칙을 하나 넣고
 * 나면 <b>그게 도움이 됐는지 알 방법이 없었다.</b> 「이을 수 있는 것이 줄었다」 는 다음 화면에서야
 * 드러나고, 그때는 어느 규칙 때문인지 모른다.
 *
 * <p>세 가지를 센다.
 * <ul>
 *   <li><b>규칙별 적중 수</b> — 아무 데도 안 걸리는 규칙은 쌓여만 있고 아무 일도 안 한다</li>
 *   <li><b>짝이 되는 품목 수</b> — 규칙을 넣기 전과 후. 이것이 이 화면의 성적표다</li>
 *   <li><b>충돌</b> — 서로 다른 품목이 같은 이름이 되는 것. <b>이것이 가장 위험하다</b></li>
 * </ul>
 *
 * <p><b>충돌을 왜 경고하는가.</b> 규칙이 지나치면 다른 물건을 한 물건으로 만든다. 그러면 대조는
 * 「재고가 맞는다」 고 말하는데 실제로는 서로 다른 두 품목이 합쳐진 것이다 — 불일치를 못 찾는
 * 것보다 나쁘다. 틀렸다는 사실조차 드러나지 않기 때문이다. 실제로 유통기한으로만 구분되는
 * 품목이 흔하다(「850g (27.03.16)」·「850g (27.04.16)」). 괄호를 떼는 순간 한 물건이 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NormalizationInsight {

    /**
     * 셈에 쓸 품명의 상한.
     *
     * <p>미리보기는 눈으로 훑을 12개면 되지만 <b>셈은 전체를 봐야</b> 뜻이 있다 — 12개 중 3개가
     * 걸렸다는 말은 아무 정보도 아니다. 그렇다고 품목이 수만 건인 곳에서 화면 열 때마다 전부
     * 돌리면 그 자체가 느려지므로 선을 둔다.
     */
    private static final int SCAN_LIMIT = 5000;

    /** 화면에 펼쳐 보일 충돌 무리 수. 나머지는 개수로만 말한다. */
    private static final int COLLISION_SHOWN = 20;

    private final NormalizationEngine engine;
    private final NormalizationRuleRepository rules;
    private final JdbcClient jdbcClient;

    /**
     * @param hits          규칙별로 이 자료에서 몇 개를 바꿨나
     * @param pairedBefore  규칙이 하나도 없을 때 양쪽 원천에서 이름이 같은 품목 수
     * @param pairedAfter   지금 규칙을 다 걸었을 때의 같은 수
     * @param collisions    서로 다른 원본이 같은 이름이 된 무리
     * @param collisionMore 화면에 못 담은 나머지 충돌 무리 수
     * @param scanned       실제로 센 품명 수
     * @param truncated     상한에 걸려 일부만 셌나
     */
    public record Insight(Map<UUID, Integer> hits, int pairedBefore, int pairedAfter,
                          List<Collision> collisions, int collisionMore, int scanned,
                          boolean truncated) {

        /** 규칙을 넣어 늘어난 짝의 수. 이 화면의 성적표다. */
        public int gained() {
            return pairedAfter - pairedBefore;
        }

        public boolean hasCollision() {
            return !collisions.isEmpty();
        }

        /** 이 규칙이 이 자료에서 아무 일도 안 하나. 화면에서 흐리게 보이는 근거다. */
        public boolean idle(UUID ruleId) {
            return hits.getOrDefault(ruleId, 0) == 0;
        }

        public int hitsOf(UUID ruleId) {
            return hits.getOrDefault(ruleId, 0);
        }
    }

    /**
     * 서로 다른 원본이 같은 이름이 된 무리.
     *
     * @param normalized 다듬은 뒤의 이름
     * @param raws       그 이름이 된 서로 다른 원본들
     */
    public record Collision(String normalized, List<String> raws) {
    }

    /** 한 품명과 그것이 어느 원천에서 왔는지. 짝을 세려면 원천을 알아야 한다. */
    private record Named(String source, String raw) {
    }

    /**
     * 제한 시간.
     *
     * <p>미리보기(표본 12개)보다 길게 둔다 — 여기는 담긴 품명 전체를 돈다. 그렇다고 없앨 수는
     * 없다. <b>사람이 넣은 정규식</b>이 도는 자리이고, 되돌아가는 패턴 하나면 요청 스레드가
     * 영영 풀려나지 않는다.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Transactional(readOnly = true)
    public Insight compute() {
        List<Named> names = scan();
        if (names.isEmpty()) {
            return new Insight(Map.of(), 0, 0, List.of(), 0, 0, false);
        }

        List<NormalizationRule> active = rules.findByIsActiveTrueOrderBySortOrder();
        return RegexGuard.runWithTimeout(TIMEOUT, () -> computeFrom(names, active));
    }

    private Insight computeFrom(List<Named> names, List<NormalizationRule> active) {

        Map<UUID, Integer> hits = new LinkedHashMap<>();
        // 다듬은 이름 → 그 이름이 된 서로 다른 원본들. 충돌 판정에 쓴다.
        Map<String, List<String>> byNormalized = new TreeMap<>();
        // 원천 → 그 원천이 가진 다듬은 이름들. 짝 세기에 쓴다.
        Map<String, java.util.Set<String>> afterBySource = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> beforeBySource = new LinkedHashMap<>();

        for (Named named : names) {
            List<NormalizationRule> forSource = active.stream()
                    .filter(rule -> rule.appliesTo(named.source()))
                    .toList();

            // 한 번 도는 동안 「어느 규칙이 이 이름을 바꿨는지」 를 함께 받는다. 규칙 하나만
            // 따로 걸어 세면 앞 규칙이 이미 바꿔 놓은 상태를 못 보므로 실제 적중과 달라진다.
            NormalizationEngine.Trace trace = engine.trace(forSource, named.raw(), RegexGuard::guard);
            trace.changedBy().forEach(ruleId -> hits.merge(ruleId, 1, Integer::sum));

            String after = trace.result();
            // 규칙이 하나도 없을 때의 이름. 성적표의 「전」 쪽이다.
            String before = engine.apply(List.of(), named.raw());

            byNormalized.computeIfAbsent(after, key -> new ArrayList<>());
            if (!byNormalized.get(after).contains(named.raw())) {
                byNormalized.get(after).add(named.raw());
            }
            afterBySource.computeIfAbsent(named.source(), key -> new java.util.HashSet<>())
                    .add(after);
            beforeBySource.computeIfAbsent(named.source(), key -> new java.util.HashSet<>())
                    .add(before);
        }

        List<Collision> collisions = byNormalized.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> new Collision(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt((Collision c) -> -c.raws().size()))
                .toList();

        return new Insight(hits,
                paired(beforeBySource), paired(afterBySource),
                collisions.stream().limit(COLLISION_SHOWN).toList(),
                Math.max(0, collisions.size() - COLLISION_SHOWN),
                names.size(), names.size() >= SCAN_LIMIT);
    }

    /**
     * 양쪽 원천에 다 있는 이름의 수.
     *
     * <p>원천이 셋 이상이면 <b>가장 많은 짝을 만드는 두 원천</b>으로 센다. 재고 대조는 두 곳을
     * 견주는 일이고, 셋을 한꺼번에 센 숫자는 어느 조합을 고쳐야 하는지 말해 주지 않는다.
     */
    private int paired(Map<String, java.util.Set<String>> bySource) {
        List<String> sources = List.copyOf(bySource.keySet());
        int best = 0;
        for (int i = 0; i < sources.size(); i++) {
            for (int j = i + 1; j < sources.size(); j++) {
                java.util.Set<String> left = new java.util.HashSet<>(bySource.get(sources.get(i)));
                left.retainAll(bySource.get(sources.get(j)));
                best = Math.max(best, left.size());
            }
        }
        return best;
    }

    /**
     * 셀 품명을 가져온다.
     *
     * <p><b>원천마다 가장 최근 것만</b> 본다. 몇 달 전 표기가 섞이면 지금은 쓰지도 않는 이름을
     * 보고 규칙의 성적을 매기게 된다.
     */
    private List<Named> scan() {
        return jdbcClient.sql("""
                        SELECT DISTINCT s.source AS source,
                                        coalesce(s.raw_item_name, '') AS raw_name
                          FROM std_stock_snapshot s
                         WHERE s.tenant_id = :tenantId
                           AND coalesce(s.raw_item_name, '') <> ''
                           AND s.base_at = (SELECT max(x.base_at) FROM std_stock_snapshot x
                                             WHERE x.tenant_id = s.tenant_id
                                               AND x.source    = s.source)
                         ORDER BY source, raw_name
                         LIMIT :limit
                        """)
                .param("tenantId", TenantContext.current())
                .param("limit", SCAN_LIMIT)
                .query((rs, rowNum) -> new Named(rs.getString("source"), rs.getString("raw_name")))
                .list();
    }
}
