package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.match.MatchBoard;
import kr.suhsaechan.palim.reconcile.rule.NormalizationEngine;
import kr.suhsaechan.palim.reconcile.rule.NormalizationRule;
import kr.suhsaechan.palim.reconcile.rule.NormalizationRuleRepository;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnit;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 대조표가 <b>줄을 어떻게 만드는가</b>, 그리고 이름 다듬기가 표기 차이를 흡수하는가.
 *
 * <p>짝을 찾는 일을 줄이되 <b>확정하지는 않는다.</b> 사람이 수백 품목을 하나씩 훑는 것은
 * 현실적이지 않다. 그렇다고 다듬은 이름이 같다고 자동으로 확정해 버리면, 규칙이 틀렸을 때
 * 엉뚱한 품목을 합쳐 놓고 「재고가 맞는다」 고 보고한다 — 불일치를 못 찾는 것보다 나쁘다.
 * 틀렸다는 사실조차 드러나지 않기 때문이다.
 */
class MatchBoardIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MatchBoard board;
    @Autowired private NormalizationEngine normalizer;
    @Autowired private NormalizationRuleRepository rules;
    @Autowired private ReconcileUnitService unitService;
    @Autowired private JdbcClient jdbcClient;

    private Instant baseAt;
    private String erp;
    private String wms;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        baseAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        erp = "erp-" + UUID.randomUUID().toString().substring(0, 6);
        wms = "wms-" + UUID.randomUUID().toString().substring(0, 6);
        // 규칙을 지우지 않는다. 기본 규칙은 마이그레이션이 심어 두는 것이고, 그것이 실제로
        // 작동하는지가 이 화면의 쓸모를 가른다 — 지우고 시험하면 「규칙이 없을 때」만 보게 된다.
        normalizer.clearCache();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void snapshot(String source, String itemRef, String rawName) {
        jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source, warehouse_code, lot_code,
                             quantity, base_quantity, base_unit, raw_item_name, created_at, updated_at)
                        VALUES (:id, :tenant, :item, :at, :source, '', '',
                                1, 1, 'EA', :name, :at, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("item", itemRef)
                .param("at", baseAt.atOffset(ZoneOffset.UTC))
                .param("source", source)
                .param("name", rawName)
                .update();
    }

    /** 괄호 안 유통기한을 떼는 규칙. 실무에서 가장 흔한 표기 차이다. */
    private void bracketRule() {
        rules.save(NormalizationRule.of(TENANT, "괄호 안 내용 제거", "\\([^)]*\\)", "", 1));
        normalizer.clearCache();
    }

    private MatchBoard.Board load(MatchBoard.Tab tab) {
        return board.load(TENANT, erp, wms, tab, null, 0);
    }

    @Test
    @DisplayName("규칙을 적용해 표기 차이를 흡수한다")
    void 표기_차이를_흡수한다() {
        bracketRule();

        assertThat(normalizer.normalize("제품A 16g (26.11.07)"))
                .as("괄호를 떼고 공백을 지우면 같은 이름이 된다")
                .isEqualTo("제품a16g");
        assertThat(normalizer.normalize("제품A16g")).isEqualTo("제품a16g");
    }

    /** 다듬은 이름이 같은 좌·우 품목은 <b>한 줄</b>이 된다. 그래야 나란히 견줄 수 있다. */
    @Test
    @DisplayName("다듬은 이름이 같으면 좌·우가 한 줄에 놓인다")
    void 같은_이름끼리_한_줄() {
        bracketRule();
        snapshot(erp, "E-1", "제품A 16g (26.11.07)");
        snapshot(wms, "W-1", "제품A16g");

        assertThat(load(MatchBoard.Tab.PAIRED).rows())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.left()).hasSize(1);
                    assertThat(row.right()).hasSize(1);
                    assertThat(row.bothSides()).isTrue();
                });
    }

    /**
     * 짝이 있는 것과 한쪽에만 있는 것은 <b>할 일이 다르다.</b>
     *
     * <p>앞은 그냥 이으면 되고, 뒤는 반대쪽을 찾아보거나 「짝 없음」 으로 두어야 한다. 섞어
     * 놓으면 사람이 줄마다 무엇을 해야 하는지 다시 판단해야 한다.
     */
    @Test
    @DisplayName("짝이 있는 것과 한쪽에만 있는 것을 갈래로 나눈다")
    void 갈래로_나눈다() {
        bracketRule();
        snapshot(erp, "E-ONLY", "한쪽에만 있는 제품");
        snapshot(erp, "E-2", "제품B 227g (27.04.07)");
        snapshot(wms, "W-2", "제품B227g");

        MatchBoard.Counts counts = load(MatchBoard.Tab.TODO).counts();
        assertThat(counts.paired()).isEqualTo(1);
        assertThat(counts.oneSided()).isEqualTo(1);
        assertThat(counts.todo())
                .as("할 일은 둘을 합친 것이다 — 이 숫자가 0이 되어야 대조를 돌릴 수 있다")
                .isEqualTo(2);
    }

    /**
     * 이미 이어 둔 품목이 <b>할 일에 다시 나타나면</b> 사람이 「또 해야 하나」 하고 헷갈린다.
     *
     * <p>그렇다고 화면에서 지우면 안 된다 — 되돌릴 자리가 사라진다. 「묶어 둔 것」 갈래에 남긴다.
     */
    @Test
    @DisplayName("이어 둔 품목은 할 일에서 빠지고 「묶어 둔 것」 에 남는다")
    void 이어_둔_것은_갈래가_다르다() {
        bracketRule();
        snapshot(erp, "E-DONE", "이미 연결된 제품");
        snapshot(wms, "W-NEW", "아직 안 된 제품");

        ReconcileUnit unit = unitService.create(
                "UNIT-" + UUID.randomUUID().toString().substring(0, 8), "이미 연결된 제품", "EA");
        unitService.confirm(unitService.propose(
                unit.getId(), erp, "E-DONE", BigDecimal.ONE).getId());

        assertThat(load(MatchBoard.Tab.TODO).rows())
                .as("묶어 둔 것이 할 일에 다시 오르면 사람이 또 해야 하나 헷갈린다")
                .noneSatisfy(row -> assertThat(row.items())
                        .anyMatch(item -> item.itemRef().equals("E-DONE")));

        assertThat(load(MatchBoard.Tab.LINKED).rows())
                .as("되돌릴 자리가 없으면 잘못 이은 것이 영영 남는다")
                .anySatisfy(row -> assertThat(row.items())
                        .anyMatch(item -> item.itemRef().equals("E-DONE")));
    }

    /**
     * 규칙 하나가 잘못됐다고 매칭 화면 전체가 열리지 않으면, 사람이 그 규칙을 고칠 수도 없다.
     */
    @Test
    @DisplayName("정규식이 잘못된 규칙은 건너뛰고 나머지를 적용한다")
    void 잘못된_규칙은_건너뛴다() {
        rules.save(NormalizationRule.of(TENANT, "깨진 규칙", "([", "", 1));
        rules.save(NormalizationRule.of(TENANT, "괄호 제거", "\\([^)]*\\)", "", 2));
        normalizer.clearCache();

        assertThat(normalizer.normalize("제품C 100g (27.01.01)"))
                .as("고칠 기회를 주려면 화면이 열려야 한다")
                .isEqualTo("제품c100g");
    }

    @Test
    @DisplayName("규칙이 없으면 공백만 지우고 소문자로 맞춘다")
    void 규칙이_없어도_동작한다() {
        assertThat(normalizer.normalize("제품 D 50g"))
                .as("띄어쓰기만 달라도 못 맞추면 규칙 없이는 아무것도 못 한다")
                .isEqualTo("제품d50g");
    }

    /**
     * <b>규칙을 손수 넣지 않아도</b> 흔한 표기 차이는 흡수되는가.
     *
     * <p>규칙이 하나도 없으면 이름이 글자 하나만 달라도 다른 물건으로 본다. 그러면 「이을 수 있는
     * 것」이 늘 비어 있고, 사람이 수백 품목을 눈으로 찾아 손으로 이어야 한다. 처음 쓰는 사람이
     * 정규식을 짜서 넣기를 기대할 수는 없다.
     *
     * <p>특히 <b>유통기한이 이름에 붙어 오는 것</b>이 흔하다. 떼지 않으면 같은 제품이 로트마다
     * 다른 물건이 되어 영영 안 묶인다.
     */
    @Test
    @DisplayName("기본 규칙만으로 유통기한 표기 차이를 흡수한다")
    void 기본_규칙이_있다() {
        normalizer.clearCache();

        assertThat(normalizer.normalize("제품A 227g (26.10.17)"))
                .as("괄호에 붙은 유통기한 — 로트가 달라도 같은 제품이다")
                .isEqualTo(normalizer.normalize("제품A 227g (27.04.07)"));

        assertThat(normalizer.normalize("C227P_26.10.17"))
                .as("품목코드에 붙은 유통기한")
                .isEqualTo(normalizer.normalize("C227P_27.04.07"));

        assertThat(normalizer.normalize("제품A-227g"))
                .as("이음 기호는 원천마다 제각각이라 이것만으로 갈리는 일이 잦다")
                .isEqualTo(normalizer.normalize("제품A 227g"));
    }
}
