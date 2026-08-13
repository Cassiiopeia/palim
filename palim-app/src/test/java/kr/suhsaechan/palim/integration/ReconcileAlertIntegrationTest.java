package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.reconcile.define.ReconcileDefinition;
import kr.suhsaechan.palim.reconcile.engine.ReconcileAlertPolicy;
import kr.suhsaechan.palim.reconcile.run.DiffState;
import kr.suhsaechan.palim.reconcile.run.DiffType;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 무엇을 알릴지.
 *
 * <p><b>알림이 잡음이 되는 순간 그 알림은 없는 것과 같아진다.</b> 매일 도는 일에서 이것은
 * 기능이 있고 없고보다 중요하다 — 사람이 알림을 꺼 버리면 진짜 문제가 났을 때도 모른다.
 */
class ReconcileAlertIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private ReconcileAlertPolicy policy;

    private ReconcileDefinition definition(String threshold) {
        return ReconcileDefinition.of(TENANT, "DEF-A", "전산 대 물류", "erp", "wms",
                "base_quantity", BigDecimal.ZERO,
                threshold == null ? null : new BigDecimal(threshold));
    }

    private ReconcileDiff diff(DiffState state, String delta) {
        UUID runId = UUID.randomUUID();
        return ReconcileDiff.of(TENANT, runId, UUID.randomUUID(), "UNIT-A",
                new BigDecimal(delta), BigDecimal.ZERO, new BigDecimal(delta),
                DiffType.LEFT_MORE, state, runId);
    }

    /**
     * 처음 보이는 차이는 반영 지연일 수 있고 다음 회차에 사라진다. 그것까지 알리면 매일
     * 헛알림이 간다.
     */
    @Test
    @DisplayName("관찰중은 알리지 않는다")
    void 관찰중은_알리지_않는다() {
        var alertable = policy.selectAlertable(definition("10"),
                List.of(diff(DiffState.OBSERVING, "100")));

        assertThat(alertable)
                .as("반영 지연일 수 있는 것까지 알리면 진짜 알림도 안 보게 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("두 번 확인된 차이는 알린다")
    void 확정된_차이는_알린다() {
        var alertable = policy.selectAlertable(definition("10"),
                List.of(diff(DiffState.CONFIRMED, "100")));

        assertThat(alertable).hasSize(1);
    }

    /** 낱개 몇 개 차이까지 알리면 알림이 잡음이 된다. */
    @Test
    @DisplayName("임계에 못 미치면 알리지 않는다")
    void 작은_차이는_알리지_않는다() {
        var alertable = policy.selectAlertable(definition("50"),
                List.of(diff(DiffState.CONFIRMED, "20")));

        assertThat(alertable).isEmpty();
    }

    /**
     * 임계를 정하지 않은 것은 «알리지 않기로 했다» 는 뜻이다. 기본값을 임의로 정해 보내면
     * 사람이 «왜 이게 오지» 하고 알림 자체를 꺼 버린다.
     */
    @Test
    @DisplayName("임계를 정하지 않았으면 알리지 않는다")
    void 임계가_없으면_알리지_않는다() {
        var alertable = policy.selectAlertable(definition(null),
                List.of(diff(DiffState.CONFIRMED, "9999")));

        assertThat(alertable).isEmpty();
    }

    /**
     * 사람이 «알면서 둔다» 고 한 것을 매번 다시 알리면, 그것이 잡음이 되어 나머지 알림까지
     * 묻는다.
     */
    @Test
    @DisplayName("알면서 두기로 한 차이는 알리지 않는다")
    void 무시하기로_한_것은_알리지_않는다() {
        var alertable = policy.selectAlertable(definition("10"),
                List.of(diff(DiffState.IGNORED, "500")));

        assertThat(alertable).isEmpty();
    }

    /** 미매칭은 «재고를 맞출 일» 이 아니라 «품목을 이을 일» 이라 알림 대상이 아니다. */
    @Test
    @DisplayName("아직 안 이어진 품목은 알림 대상이 아니다")
    void 미매칭은_알리지_않는다() {
        UUID runId = UUID.randomUUID();
        ReconcileDiff unmatched = ReconcileDiff.of(TENANT, runId, null, "이름 없는 제품",
                new BigDecimal("30"), BigDecimal.ZERO, new BigDecimal("30"),
                DiffType.UNMATCHED_LEFT, DiffState.OBSERVING, runId);

        assertThat(policy.selectAlertable(definition("10"), List.of(unmatched))).isEmpty();
    }
}
