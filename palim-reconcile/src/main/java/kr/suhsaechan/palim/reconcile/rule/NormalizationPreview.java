package kr.suhsaechan.palim.reconcile.rule;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 규칙을 <b>지금 담긴 품명에 걸어 본다</b> — 저장하기 전에.
 *
 * <p>규칙 화면에서 정규식을 고쳐 저장하면 그 결과가 매칭 화면에 나타나는데, 잘못 고쳤을 때
 * 보이는 것은 <b>「묶을 만한 것이 줄었다」 뿐</b>이다. 왜 줄었는지는 어디에도 안 나온다.
 * 저장 전에 실제 품명이 어떻게 바뀌는지 보여주면 그 왕복이 사라진다.
 *
 * <h2>왜 딴 스레드에서 도는가</h2>
 *
 * <p>사람이 정규식을 <b>직접 넣는</b> 화면이다. {@code (a+)+$} 같은 되돌아가는 패턴 하나면
 * 매칭이 사실상 끝나지 않는다. 그런데 <b>톰캣은 이미 돌고 있는 요청 스레드를 죽이지 않는다</b> —
 * 브라우저가 기다리다 포기해도 그 스레드는 계속 돈다. 몇 번 반복하면 스레드 풀이 마르고
 * 서버 전체가 응답을 멈춘다.
 *
 * <p>{@link RegexGuard} 로 감싼 입력은 인터럽트를 확인하므로, 시간을 넘기면 실제로 멈춘다.
 * 자바의 {@code Matcher} 자체는 인터럽트를 보지 않아서 이 감싸기가 없으면 취소가 안 먹는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NormalizationPreview {

    /** 사람이 기다릴 수 있는 선. 정상 규칙은 수십 건에 밀리초면 끝난다. */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    /** 미리보기에 보여줄 품명 수. 눈으로 훑을 수 있는 만큼이면 된다. */
    public static final int SAMPLE_SIZE = 12;

    /**
     * <b>원천마다</b> 몇 개씩 뽑을지.
     *
     * <p>전체에서 가나다순으로 열두 개를 뽑으면 <b>한쪽 원천 이름만 나오는 일</b>이 생긴다. 그러면
     * 「다듬으면 이렇게 됩니다」 는 보여도 「그래서 반대쪽과 붙습니까」 는 볼 수가 없다 — 규칙을
     * 고치는 목적이 바로 그 붙이는 것인데.
     *
     * <p>원천이 셋 이상이면 표본이 그만큼 늘어난다. 그것이 맞다 — 어느 원천이 안 붙는지 보려면
     * 그 원천 이름이 표본에 있어야 한다.
     */
    private static final int PER_SOURCE = SAMPLE_SIZE / 2;

    private final NormalizationEngine engine;
    private final NormalizationRuleRepository rules;
    private final JdbcClient jdbcClient;

    /**
     * 규칙 하나를 <b>덧붙여</b> 걸어 본 결과.
     *
     * @param candidate 아직 저장하지 않은 규칙. {@code null} 이면 지금 켜져 있는 규칙만 건다
     */
    @Transactional(readOnly = true)
    public List<Line> preview(NormalizationRule candidate) {
        return preview(candidate, sampleNames());
    }

    /**
     * 걸어 볼 품명을 <b>직접 주는</b> 길.
     *
     * <p>화면은 위 {@link #preview(NormalizationRule)} 만 쓴다. 이쪽은 시험이 «어떤 품명에»
     * 걸리는지를 정해야 할 때 쓴다 — 담긴 자료에서 뽑는 쪽은 다른 자료가 섞이면 무엇이 표본이
     * 될지 정해지지 않아, 제한 시간 같은 것을 확인할 수가 없다.
     */
    @Transactional(readOnly = true)
    public List<Line> preview(NormalizationRule candidate, List<String> samples) {
        List<NormalizationRule> active = rules.findByIsActiveTrueOrderBySortOrder();
        List<NormalizationRule> withCandidate = candidate == null
                ? active
                : java.util.stream.Stream.concat(active.stream(), java.util.stream.Stream.of(candidate))
                        .sorted(java.util.Comparator.comparingInt(NormalizationRule::getSortOrder))
                        .toList();

        return RegexGuard.runWithTimeout(TIMEOUT, () -> samples.stream()
                .map(raw -> new Line(raw,
                        engine.apply(active, raw, RegexGuard::guard),
                        candidate == null ? null
                                : engine.apply(withCandidate, raw, RegexGuard::guard)))
                .toList());
    }

    /**
     * 지금 담긴 재고에서 품명을 몇 개 가져온다.
     *
     * <p>지어낸 예시를 쓰지 않는다. 규칙이 통하는지는 <b>이 회사의 실제 품명</b>에서만 알 수
     * 있고, 지어낸 이름으로는 「잘 되는 것처럼」 보이고 끝난다.
     *
     * <p><b>원천마다 가장 최근 것만</b> 본다. 그러지 않으면 몇 달 전 표기가 표본에 섞여, 지금은
     * 쓰지도 않는 이름을 보고 규칙을 만들게 된다.
     */
    @Transactional(readOnly = true)
    public List<String> sampleNames() {
        return jdbcClient.sql("""
                        SELECT raw_name
                          FROM (SELECT source, raw_name,
                                       row_number() OVER (PARTITION BY source
                                                          ORDER BY raw_name) AS rn
                                  FROM (SELECT DISTINCT s.source                       AS source,
                                                        coalesce(s.raw_item_name, '')  AS raw_name
                                          FROM std_stock_snapshot s
                                         WHERE s.tenant_id = :tenantId
                                           AND coalesce(s.raw_item_name, '') <> ''
                                           AND s.base_at = (SELECT max(x.base_at)
                                                              FROM std_stock_snapshot x
                                                             WHERE x.tenant_id = s.tenant_id
                                                               AND x.source    = s.source)) distinct_names
                               ) ranked
                         WHERE rn <= :perSource
                         ORDER BY source, raw_name
                        """)
                .param("tenantId", TenantContext.current())
                .param("perSource", PER_SOURCE)
                .query(String.class)
                .list();
    }

    /**
     * 지금 담긴 재고에 실제로 들어 있는 원천 목록.
     *
     * <p>규칙을 어느 원천에 걸지 고르는 자리가 쓴다. 커넥터 목록이 아니라 <b>담긴 자료</b>에서
     * 뽑는다 — 만들다 만 커넥터까지 나오면 고를 수는 있는데 아무 자료에도 안 걸리는 값이
     * 섞이고, 규칙이 왜 동작하지 않는지 알 방법이 없어진다.
     */
    @Transactional(readOnly = true)
    public List<String> sources() {
        return jdbcClient.sql("""
                        SELECT DISTINCT source
                          FROM std_stock_snapshot
                         WHERE tenant_id = :tenantId
                           AND coalesce(source, '') <> ''
                         ORDER BY source
                        """)
                .param("tenantId", TenantContext.current())
                .query(String.class)
                .list();
    }

    /** 정규식이 컴파일되는지 본다. 안 되면 저장 뒤 조용히 건너뛰어져 매칭이 이유 없이 줄어든다. */
    public void validate(String pattern) {
        try {
            java.util.regex.Pattern.compile(pattern);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new BusinessException(ErrorCode.NORMALIZATION_RULE_INVALID,
                    e.getDescription());
        }
    }

    /**
     * 미리보기 한 줄.
     *
     * @param after     지금 켜져 있는 규칙까지 적용한 결과
     * @param candidate 새 규칙까지 적용한 결과. 새 규칙이 없으면 {@code null}
     */
    public record Line(String raw, String after, String candidate) {

        /** 새 규칙이 결과를 바꾸나. 안 바꾸면 그 규칙은 이 품명에 아무 일도 안 한 것이다. */
        public boolean changed() {
            return candidate != null && !candidate.equals(after);
        }
    }

    /** 아직 저장하지 않은 규칙을 만든다. 미리보기 전용이라 저장소에 넣지 않는다. */
    public static NormalizationRule candidate(UUID tenantId, String name, String pattern,
                                              String replacement, int sortOrder) {
        return NormalizationRule.of(tenantId, name, pattern, replacement, sortOrder);
    }
}
