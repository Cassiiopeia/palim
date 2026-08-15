package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import kr.suhsaechan.palim.reconcile.match.MatchCandidateFinder;
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
 * 짝을 찾는 일을 줄이되 <b>확정하지는 않는다.</b>
 *
 * <p>사람이 수백 품목을 하나씩 훑는 것은 현실적이지 않다. 그렇다고 정규화 결과가 같다고 자동으로
 * 묶어 버리면, 규칙이 틀렸을 때 엉뚱한 품목을 합쳐 놓고 "재고가 맞는다"고 보고한다 — 불일치를
 * 못 찾는 것보다 나쁘다. 틀렸다는 사실조차 드러나지 않기 때문이다.
 */
class MatchCandidateIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private MatchCandidateFinder finder;
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

    @Test
    @DisplayName("규칙을 적용해 표기 차이를 흡수한다")
    void 표기_차이를_흡수한다() {
        bracketRule();

        assertThat(normalizer.normalize("제품A 16g (26.11.07)"))
                .as("괄호를 떼고 공백을 지우면 같은 이름이 된다")
                .isEqualTo("제품a16g");
        assertThat(normalizer.normalize("제품A16g")).isEqualTo("제품a16g");
    }

    @Test
    @DisplayName("정규화 결과가 같은 것끼리 묶어 보여준다")
    void 같은_이름끼리_묶는다() {
        bracketRule();
        snapshot(erp, "E-1", "제품A 16g (26.11.07)");
        snapshot(wms, "W-1", "제품A16g");

        var candidates = finder.suggest(TENANT, erp, wms);

        assertThat(candidates)
                .filteredOn(MatchCandidateFinder.MatchCandidate::hasBothSides)
                .singleElement()
                .satisfies(c -> assertThat(c.items()).hasSize(2));
    }

    /**
     * 양쪽에서 하나씩 온 묶음이 바로 이어 붙일 수 있는 짝이다. 한쪽뿐인 것은 사람이 새 단위를
     * 만들어야 하므로 손이 더 간다 — 쉬운 것부터 처리하게 앞에 둔다.
     */
    @Test
    @DisplayName("양쪽이 섞인 묶음을 앞에 보여준다")
    void 짝이_있는_것을_먼저_보여준다() {
        bracketRule();
        snapshot(erp, "E-ONLY", "한쪽에만 있는 제품");
        snapshot(erp, "E-2", "제품B 227g (27.04.07)");
        snapshot(wms, "W-2", "제품B227g");

        var candidates = finder.suggest(TENANT, erp, wms);

        assertThat(candidates.getFirst().hasBothSides())
                .as("쉬운 것부터 처리할 수 있어야 한다")
                .isTrue();
    }

    /**
     * 이미 확정된 품목이 다시 후보로 올라오면 사람이 «또 해야 하나» 하고 헷갈린다.
     */
    @Test
    @DisplayName("이미 연결된 품목은 후보에서 뺀다")
    void 연결된_것은_빼고_보여준다() {
        bracketRule();
        snapshot(erp, "E-DONE", "이미 연결된 제품");
        snapshot(wms, "W-NEW", "아직 안 된 제품");

        ReconcileUnit unit = unitService.create(
                "UNIT-" + UUID.randomUUID().toString().substring(0, 8), "이미 연결된 제품", "EA");
        unitService.confirm(unitService.propose(
                unit.getId(), erp, "E-DONE", BigDecimal.ONE).getId());

        var candidates = finder.suggest(TENANT, erp, wms);

        assertThat(candidates)
                .as("확정된 것이 다시 올라오면 사람이 또 해야 하나 헷갈린다")
                .noneSatisfy(c -> assertThat(c.items())
                        .anyMatch(i -> i.itemRef().equals("E-DONE")));
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
     * <p>규칙이 하나도 없으면 이름이 글자 하나만 달라도 다른 물건으로 본다. 그러면 「이을 만한
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
