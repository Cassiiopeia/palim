package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.connector.run.ConnectorRun;
import kr.suhsaechan.palim.connector.run.ConnectorRunRepository;
import kr.suhsaechan.palim.connector.run.RunMode;
import kr.suhsaechan.palim.connector.run.RunTrigger;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinitionRepository;
import kr.suhsaechan.palim.web.connector.ConnectorRemovalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 지울 수 있는가, 지우면 딸린 것이 함께 사라지는가.
 *
 * <p>예전 규칙은 「쓴 적 없는 것만 지우고 그 외에는 끈다」 였다. 뜻은 옳았지만 결과는
 * 막다른 길이었다 — 실행 이력을 지우는 경로가 어디에도 없어 <b>한 번이라도 돈 연동은
 * 영원히 지울 수 없었고</b>, 목록의 「지우기」 는 눌러도 경고만 뜨는 죽은 버튼이었다.
 *
 * <p>지금은 관계를 DB 가 안다({@code V33__connector_cascade.sql}). 그래서 이 테스트가 볼 것은
 * 두 가지다.
 *
 * <ul>
 *   <li><b>딸린 것이 정말 따라 지워지는가</b> — 자바 코드가 아니라 외래키가 지우므로,
 *       마이그레이션이 실제로 붙었는지는 지워 보는 수밖에 없다. 안 붙어 있으면 주인 없는
 *       행이 조용히 남고 아무도 모른다
 *   <li><b>막아야 할 것은 여전히 막히는가</b> — 대조 정의는 연동을 code 문자열로 가리켜
 *       외래키를 걸 수 없다. 여기가 뚫리면 대조가 다음 날 아침 조용히 깨진다
 * </ul>
 */
class ConnectorRemovalIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private ConnectorRemovalService removal;
    @Autowired private ConnectorRepository connectors;
    @Autowired private ConnectorRunRepository runs;
    @Autowired private ReconcileDefinitionRepository definitions;
    @Autowired private TargetModelRepository targetModels;
    @Autowired private JdbcClient jdbc;

    private TargetModel model;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        model = targetModels.findByTenantIdAndCode(TENANT, "std_stock_snapshot").orElseThrow();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Connector connector(String prefix) {
        return connectors.save(Connector.of(TENANT,
                prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                "시험 연동", model.getId(), SourceType.HTTP_API, "EA"));
    }

    private ConnectorRun run(Connector connector) {
        return runs.save(ConnectorRun.start(TENANT, connector.getId(), UUID.randomUUID(), 1,
                RunMode.LIVE, RunTrigger.MANUAL));
    }

    /**
     * 그 실행이 담은 재고 한 줄.
     *
     * <p>{@code StandardModelWriter} 를 거치지 않고 직접 넣는다 — 여기서 보려는 것은 적재가
     * 아니라 <b>run_id 로 매달린 행이 따라 지워지는가</b> 이므로, 매달린 사실만 있으면 된다.
     */
    private UUID snapshot(ConnectorRun run, String source) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO std_stock_snapshot
                    (id, tenant_id, run_id, item_ref, base_at, source,
                     quantity, base_quantity, base_unit, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(id, TENANT, run.getId(), "ITEM-" + id.toString().substring(0, 8),
                        OffsetDateTime.now(ZoneOffset.UTC), source,
                        BigDecimal.ONE, BigDecimal.ONE, "EA",
                        OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC))
                .update();
        return id;
    }

    private boolean snapshotExists(UUID id) {
        return jdbc.sql("SELECT count(*)::int FROM std_stock_snapshot WHERE id = ?")
                .param(id).query(Integer.class).single() > 0;
    }

    /** 만들어 두고 쓰지 않은 것. 남겨 두면 목록이 지저분해져 어느 것이 진짜인지 흐려진다. */
    @Test
    @DisplayName("쓴 적 없는 연동은 지울 수 있다")
    void 안_쓴_것은_지운다() {
        Connector unused = connector("unused");

        assertThat(removal.blockedReason(unused.getId())).isNull();
        removal.remove(unused.getId());

        assertThat(connectors.findById(unused.getId())).isEmpty();
    }

    /**
     * 자료를 담은 적이 있어도 지울 수 있다 — 그 자료와 <b>함께</b>.
     *
     * <p>예전에는 여기서 막았다. 그런데 실행 이력을 지우는 경로가 없어 「끄기」 외에는 아무
     * 선택지가 없었고, 만들다 만 연동조차 목록에 영구히 남았다. 실행 이력은 연동에 딸린
     * 부산물이므로 함께 사라지는 것이 맞다.
     */
    @Test
    @DisplayName("자료를 담은 적이 있어도 지울 수 있다")
    void 담은_적이_있어도_지운다() {
        Connector used = connector("used");
        run(used);

        assertThat(removal.blockedReason(used.getId()))
                .as("실행 이력은 더 이상 막는 사유가 아니다 — 함께 지워지기 때문이다")
                .isNull();
    }

    /**
     * 딸린 것이 따라 지워지는가 — <b>외래키가</b>.
     *
     * <p>이 검증이 없으면 마이그레이션이 붙지 않아도 테스트가 통과한다. 그러면 연동만 사라지고
     * 실행 이력과 재고 행은 주인 없이 남아, 대조가 그 자료를 계속 집어 가면서도 어디서 온
     * 것인지는 아무도 답할 수 없는 상태가 된다.
     */
    @Test
    @DisplayName("지우면 실행 이력과 담긴 재고가 함께 사라진다")
    void 딸린_것이_함께_지워진다() {
        Connector used = connector("cascade");
        ConnectorRun executed = run(used);
        UUID stock = snapshot(executed, used.getCode());

        assertThat(removal.runCount(used.getId()))
                .as("지우기 전에 몇 건이 사라질지 말할 수 있어야 한다")
                .isEqualTo(1);

        removal.remove(used.getId());

        assertThat(connectors.findById(used.getId())).isEmpty();
        assertThat(runs.findById(executed.getId()))
                .as("실행 이력이 남으면 어느 연동의 것인지 아무도 답할 수 없다")
                .isEmpty();
        assertThat(snapshotExists(stock))
                .as("담긴 재고가 남으면 출처 없는 채로 대조에 계속 잡힌다")
                .isFalse();
    }

    /**
     * 손으로 넣은 자료는 남는다.
     *
     * <p>{@code run_id} 가 비어 있는 행은 어떤 연동에도 매달려 있지 않다. 연동을 지웠다고
     * 이런 행까지 사라지면, 연동과 무관하게 쌓아 둔 자료가 남의 삭제에 휩쓸린다.
     */
    @Test
    @DisplayName("연동에 매달리지 않은 재고는 지워지지 않는다")
    void 매달리지_않은_것은_남는다() {
        Connector used = connector("keep");
        ConnectorRun executed = run(used);
        UUID orphanByDesign = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO std_stock_snapshot
                    (id, tenant_id, run_id, item_ref, base_at, source,
                     quantity, base_quantity, base_unit, created_at, updated_at)
                VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(orphanByDesign, TENANT,
                        "MANUAL-" + orphanByDesign.toString().substring(0, 8),
                        OffsetDateTime.now(ZoneOffset.UTC), "MANUAL",
                        BigDecimal.ONE, BigDecimal.ONE, "EA",
                        OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC))
                .update();
        snapshot(executed, used.getCode());

        removal.remove(used.getId());

        assertThat(snapshotExists(orphanByDesign))
                .as("run_id 가 비어 있는 행은 CASCADE 대상이 아니다")
                .isTrue();
    }

    /**
     * 대조가 쓰고 있으면 막는다.
     *
     * <p>대조 정의는 연동을 <b>코드 이름으로</b> 가리킨다. 외래키가 아니라서 지워도 DB 는
     * 아무 말이 없고, 다음 대조에서야 「비교할 재고가 없습니다」 로 나타난다. 그래서 여기만은
     * 사람 말로 막는다 — 무엇을 먼저 손봐야 하는지까지 말해야 길이 된다.
     */
    @Test
    @DisplayName("대조가 쓰고 있으면 막고 어느 대조인지 말한다")
    void 대조가_쓰면_막는다() {
        Connector left = connector("left");
        Connector right = connector("right");
        definitions.save(ReconcileDefinition.of(TENANT,
                "def-" + UUID.randomUUID().toString().substring(0, 6), "전산 대 물류",
                left.getCode(), right.getCode(), "quantity",
                BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(removal.blockedReason(left.getId()))
                .as("어느 대조가 쓰는지 알아야 사람이 판단할 수 있다")
                .contains("대조가 이 연동을 쓰고 있습니다")
                .contains("전산 대 물류")
                .as("막기만 하면 막다른 길이다. 다음에 할 일을 말해야 한다")
                .contains("바꾼 뒤에 지우세요");
    }

    /** 끄기는 지우기와 다르다 — 수집만 멈추고 담긴 자료·대조 정의는 그대로 산다. */
    @Test
    @DisplayName("끄면 수집만 멈추고 연동은 남는다")
    void 끄면_남는다() {
        Connector connector = connector("toggle");

        removal.changeEnabled(connector.getId(), false);

        Connector after = connectors.findById(connector.getId()).orElseThrow();
        assertThat(after.isEnabled()).isFalse();
        assertThat(after.getCode())
                .as("끈 것은 지운 것이 아니다. 담긴 자료가 이 이름을 가리킨다")
                .isEqualTo(connector.getCode());
    }
}
