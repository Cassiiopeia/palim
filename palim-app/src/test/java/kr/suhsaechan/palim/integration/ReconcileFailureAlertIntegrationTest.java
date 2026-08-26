package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.notification.NotificationOutbox;
import kr.suhsaechan.palim.notification.NotificationOutboxRepository;
import kr.suhsaechan.palim.notification.NotificationType;
import kr.suhsaechan.palim.notification.payload.ReconcileDigestPayload;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.reconcile.engine.ReconcileScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 자동 대조가 <b>계속 안 도는 것</b>을 사람이 알게 되는가.
 *
 * <p>지금까지 자동 대조는 실패하면 로그만 남기고 넘어갔다. 그 판단 자체는 옳다 — 수집이 늦어
 * 기준 시각이 어긋난 것은 다음 회차에 저절로 풀리고, 그때마다 알리면 사람이 알림을 꺼 버린다.
 *
 * <p><b>그런데 영영 안 풀리는 실패도 똑같이 생겼다.</b> 설정이 깨졌거나 한쪽 수집이 멈춘
 * 경우인데, 이 자리에서 그냥 돌아섰기 때문에 <b>몇 주를 안 돌아도 아무도 몰랐다.</b> 조용한
 * 실패를 막으려고 만든 장치들이 정작 이 문 앞에서 전부 조용해졌다.
 *
 * <p>그래서 연속 실패를 센다. 이 시험은 <b>양쪽</b>을 본다 — 한두 번은 조용하고, 사흘째에
 * 부른다. 한쪽만 잠그면 「알리긴 하는데 매일 알린다」 는 반대쪽 사고가 열린다.
 */
class ReconcileFailureAlertIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private ReconcileScheduler scheduler;
    @Autowired private ReconcileDefinitionRepository definitions;
    @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbcClient;
    @Autowired private NotificationOutboxRepository outbox;
    @Autowired private kr.suhsaechan.palim.notification.OutboxService outboxService;
    @Autowired private kr.suhsaechan.palim.reconcile.engine.ReconcileEngine engine;

    private ReconcileDefinition definition;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        // 하루 요약은 «활성 대조 전부» 를 한 통으로 접는다. 그래서 다른 시험이 남긴 대조가
        // 그대로 있으면 이 시험의 통에 함께 담긴다 — 전역 집계라는 성질 자체가 그렇다.
        // 각 시험이 자기 것만 보도록 앞서 남은 것을 치운다.
        jdbcClient.sql("DELETE FROM reconcile_diff").update();
        jdbcClient.sql("DELETE FROM reconcile_run").update();
        jdbcClient.sql("DELETE FROM reconcile_definition").update();
        jdbcClient.sql("DELETE FROM notification_outbox").update();

        // 양쪽 다 담긴 재고가 없다 — 대조는 RECONCILE_SNAPSHOT_MISSING 으로 실패한다.
        // 「저절로 안 풀리는 실패」 의 가장 흔한 모양이라 이대로 쓴다.
        definition = definitions.save(ReconcileDefinition.of(TENANT,
                "blocked-" + UUID.randomUUID().toString().substring(0, 6), "막힌 대조",
                "erp-" + UUID.randomUUID().toString().substring(0, 6),
                "wms-" + UUID.randomUUID().toString().substring(0, 6),
                "base_quantity", BigDecimal.ZERO, null));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 지난 날짜에 실패한 회차를 심는다.
     *
     * <p>셈이 <b>회차가 아니라 날</b>이라 같은 날 여러 번 돌려서는 사흘이 되지 않는다.
     * 그것이 의도다 — 사람이 화면에서 몇 번 눌러 본 것이 「사흘째」 로 둔갑하면 안 된다.
     * 그래서 시험은 실제로 날이 걸친 상태를 만든다.
     */
    private void failedRunDaysAgo(int daysAgo) {
        Instant at = Instant.now().minus(daysAgo, ChronoUnit.DAYS);
        jdbcClient.sql("""
                        INSERT INTO reconcile_run
                            (id, tenant_id, definition_id, base_at, status,
                             left_count, right_count, diff_count, unmatched_count,
                             started_at, finished_at, message, created_at, updated_at)
                        VALUES (:id, :tenant, :definition, :at, 'FAILED',
                                0, 0, 0, 0, :at, :at, '지난 실패', :at, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("definition", definition.getId())
                // JdbcClient 는 Instant 를 바인딩하지 못한다. timestamptz 에는 OffsetDateTime.
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * 하루 요약이 <b>제목에</b> 막힘을 올렸는가.
     *
     * <p>막힘은 이제 따로 알리지 않고 하루 한 통에 담긴다. 그래서 「불렀는가」 는 별개 알림이
     * 있는지가 아니라 <b>제목이 막힘을 말하는지</b>로 본다 — 사람이 실제로 보는 것이 그것이다.
     */
    private List<ReconcileDigestPayload> headlinedBlocked() {
        return outbox.findAll().stream()
                .filter(row -> row.getType() == NotificationType.RECONCILE_DIGEST)
                .map(row -> outboxService.readPayload(row, ReconcileDigestPayload.class))
                .filter(payload -> !payload.blocked().isEmpty())
                .toList();
    }

    /** 오늘 요약. 매일 한 통이므로 하나뿐이다. */
    private ReconcileDigestPayload digest() {
        return outbox.findAll().stream()
                .filter(row -> row.getType() == NotificationType.RECONCILE_DIGEST)
                .map(row -> outboxService.readPayload(row, ReconcileDigestPayload.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError("요약이 한 통도 없다"));
    }

    /**
     * 하루 못 돈 것으로는 부르지 않는다.
     *
     * <p>수집이 늦은 날마다 알리면 사람이 알림 자체를 꺼 버리고, 그러면 정작 봐야 할 것도
     * 못 본다. 이 조용함은 게으름이 아니라 설계다.
     */
    @Test
    @DisplayName("하루 이틀 막힌 것으로는 알리지 않는다")
    void 하루_이틀은_조용하다() {
        scheduler.runAll();
        assertThat(headlinedBlocked()).as("첫날은 다음 회차에 풀릴 수 있다").isEmpty();

        // 다만 «본문» 에는 담긴다. 담지 않으면 「오늘 대조가 안 돌았다」 가 통째로 사라진다.
        assertThat(digest().lines())
                .as("제목에 안 올리는 것과 아예 말하지 않는 것은 다르다")
                .anyMatch(line -> line.contains("막힘"));
    }

    /**
     * 같은 날 여러 번 눌러 본 것은 <b>하루다.</b>
     *
     * <p>회차를 세면 사람이 오전에 세 번 눌러 봤다는 이유만으로 「저절로 풀리지 않는 상태」
     * 알림이 나간다. 실행 이력은 스케줄러만의 것이 아니다.
     */
    @Test
    @DisplayName("같은 날 여러 번 실패한 것은 하루로 센다")
    void 같은_날은_하루다() {
        scheduler.runAll();
        engine.run(definition.getId());
        engine.run(definition.getId());
        scheduler.runAll();

        assertThat(headlinedBlocked())
                .as("눌러 본 횟수가 «며칠째» 로 둔갑하면 안 된다")
                .isEmpty();
    }

    /**
     * <b>사흘 연속이면 저절로 풀리는 종류가 아니다.</b> 이때는 사람이 손대야 한다.
     */
    @Test
    @DisplayName("사흘 연속 막히면 사람을 부른다")
    void 사흘째에_부른다() {
        failedRunDaysAgo(2);
        failedRunDaysAgo(1);
        scheduler.runAll();
        assertThat(headlinedBlocked())
                .as("로그에만 남기면 몇 주를 안 돌아도 아무도 모른다")
                .hasSize(1);

        // 받는 쪽이 읽는 모양 그대로 되읽는다.
        ReconcileDigestPayload payload = digest();
        assertThat(payload.blocked())
                .as("어느 대조인지 모르면 어디를 봐야 할지 알 수 없다")
                .anyMatch(blocked -> blocked.definition().equals("막힌 대조")
                        && blocked.days() >= 3);
        assertThat(payload.subject())
                .as("열지 않아도 판단하려면 제목이 막힘을 말해야 한다")
                .contains("막힘");
        assertThat(payload.needsAttention()).isTrue();
    }

    /**
     * 하루에 여러 번 돌아도 <b>통은 하나다.</b>
     *
     * <p>예전에는 「고장 한 번에 부름 한 번」 이었다 — 막힘이 별개 알림이라 매일 부르면 소음이
     * 됐기 때문이다. 이제는 요약이 어차피 매일 한 통 오므로 <b>막힌 상태가 매일 제목에 뜨는
     * 것이 맞다.</b> 안 고쳤으니 계속 말하는 것이고, 그것이 「열지 않아도 판단」 이다.
     *
     * <p>대신 막아야 하는 것은 <b>같은 날 여러 통</b>이다.
     */
    @Test
    @DisplayName("하루에 여러 번 돌아도 요약은 한 통이다")
    void 넘은_뒤로는_조용하다() {
        failedRunDaysAgo(4);
        failedRunDaysAgo(3);
        failedRunDaysAgo(2);
        failedRunDaysAgo(1);
        for (int i = 0; i < 3; i++) {
            scheduler.runAll();
        }

        assertThat(headlinedBlocked())
                .as("하루에 여러 번 돌아도 통은 하나다")
                .hasSize(1);
    }

    /**
     * <b>셈이 건너뛰어도 알려야 한다.</b>
     *
     * <p>사람이 화면에서 「지금 맞춰 보기」 를 누르면 그 실패도 이력에 쌓인다. 그래서 연속
     * 횟수는 2에서 4로 뛸 수 있다. 문턱을 「정확히 같을 때」 로 보면 그 순간 이 장치가 영영
     * 조용해지는데, <b>조용한 실패를 막으려고 만든 장치가 조용히 죽는</b> 것이야말로 이 작업이
     * 없애려던 바로 그 모양이다.
     */
    @Test
    @DisplayName("사이에 수동 실행이 끼어 셈이 건너뛰어도 알린다")
    void 셈이_건너뛰어도_알린다() {
        failedRunDaysAgo(2);
        failedRunDaysAgo(1);
        // 사람이 화면에서 눌러 본 것이 하나 더 끼어든다 — 회차로 세면 문턱을 지나쳐 버린다
        engine.run(definition.getId());

        scheduler.runAll();

        assertThat(headlinedBlocked())
                .as("문턱을 «정확히» 지나쳤다고 침묵하면 안 된다")
                .hasSize(1);
    }

    /**
     * 알림 내용이 <b>받는 쪽이 읽는 모양</b>인가.
     *
     * <p>예전에는 대조 알림이 동결 도메인의 「재고 불일치」 종류를 빌려 썼다. 칸 이름이 하나도
     * 안 겹쳐서 받는 쪽은 「재고 수량 0개 / 이력 누적합 0개」 라는 <b>빈 알림</b>을 그렸다 —
     * 오류가 아니라 성공한 것처럼 보이는 빈 값이라 아무도 이상하다고 여기지 않는다.
     */
    @Test
    @DisplayName("대조 알림은 재고 불일치 알림과 섞이지 않는다")
    void 종류가_섞이지_않는다() {
        failedRunDaysAgo(2);
        failedRunDaysAgo(1);
        scheduler.runAll();

        assertThat(outbox.findAll())
                .filteredOn(row -> row.getType() == NotificationType.STOCK_MISMATCH)
                .as("동결 도메인의 «한 시스템 안에서 어긋남» 과 대조는 다른 사건이다")
                .isEmpty();
    }
}
