package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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

/**
 * 지워도 되는 것만 지우는가.
 *
 * <p>DB 에 외래키가 없어 <b>지우는 것 자체는 아무것도 막아주지 않는다.</b> 자료를 담은 연동을
 * 지우면 대조가 다음 날 아침 조용히 깨지고, 화면에는 「비교할 재고가 없습니다」 만 떠서 원인을
 * 알 수 없다. 몇 주 뒤에 「그때 뭘 지웠더라」 를 되짚게 된다.
 *
 * <p>그래서 규칙은 <b>「쓴 적 없는 것만 지우고, 그 외에는 끈다」</b> 이다. 이 테스트는 그
 * 규칙이 실제로 서는지 본다 — 막는 쪽이 안 막히면 자료가 조용히 무너진다.
 */
class ConnectorRemovalIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private ConnectorRemovalService removal;
    @Autowired private ConnectorRepository connectors;
    @Autowired private ConnectorRunRepository runs;
    @Autowired private ReconcileDefinitionRepository definitions;
    @Autowired private TargetModelRepository targetModels;

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
     * 자료를 담은 적이 있으면 막는다.
     *
     * <p>담긴 자료는 <b>출처 이름으로</b> 이 연동을 가리킨다. 지우면 그 자료가 어디서 왔는지
     * 설명할 방법이 사라진다.
     */
    @Test
    @DisplayName("자료를 담은 적이 있으면 막고 이유를 말한다")
    void 담은_적이_있으면_막는다() {
        Connector used = connector("used");
        runs.save(ConnectorRun.start(TENANT, used.getId(), UUID.randomUUID(), 1,
                RunMode.LIVE, RunTrigger.MANUAL));

        assertThat(removal.blockedReason(used.getId()))
                .as("「지울 수 없습니다」 만으로는 무엇을 해야 하는지 알 수 없다")
                .contains("자료를 담은 적이 있습니다")
                .contains("끄기");
    }

    /**
     * 대조가 쓰고 있으면 막는다.
     *
     * <p>대조 정의는 연동을 <b>코드 이름으로</b> 가리킨다. 외래키가 아니라서 지워도 DB 는
     * 아무 말이 없고, 다음 대조에서야 「비교할 재고가 없습니다」 로 나타난다.
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
                .contains("전산 대 물류");
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
