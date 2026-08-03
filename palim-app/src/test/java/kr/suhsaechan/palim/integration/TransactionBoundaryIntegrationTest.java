package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.incident.IncidentService;
import kr.suhsaechan.palim.incident.IncidentType;
import kr.suhsaechan.palim.sku.SkuService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * 트랜잭션 경계가 런타임에 강제되는지 검증한다.
 *
 * <p>설계서 3.4는 "도메인 서비스는 자체 트랜잭션을 열지 않고 참여만 한다"고 정했다. 단순히
 * {@code @Transactional} 을 생략하면 호출자가 트랜잭션을 잊었을 때 재고 변경과 이력 기록이
 * 각각 커밋되어, 중간 실패 시 정합성이 깨진 채 조용히 넘어간다.
 *
 * <p>변경 메서드는 {@code Propagation.MANDATORY} 이므로 트랜잭션 없이 호출하면 즉시 예외가
 * 발생한다. <b>이 테스트 클래스에는 의도적으로 {@code @Transactional} 을 붙이지 않는다.</b>
 */
class TransactionBoundaryIntegrationTest extends IntegrationTest {

    @Autowired
    private SkuService skuService;

    @Autowired
    private IncidentService incidentService;

    @Test
    @DisplayName("트랜잭션 없이 SKU 등록을 호출하면 예외가 발생한다")
    void 트랜잭션_없는_등록은_거부된다() {
        assertThatThrownBy(() -> skuService.register("SKU-TX-1", "트랜잭션 테스트", 10, 5))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("트랜잭션 없이 재고 차감을 호출하면 예외가 발생한다")
    void 트랜잭션_없는_차감은_거부된다() {
        assertThatThrownBy(() -> skuService.decreaseForSale(
                java.util.UUID.randomUUID(), 1, java.util.UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("트랜잭션 없이 인시던트 보고를 호출하면 예외가 발생한다")
    void 트랜잭션_없는_인시던트_보고는_거부된다() {
        // 감지 지점의 트랜잭션에 참여해야 수집 롤백 시 유령 인시던트가 남지 않는다 (#35).
        assertThatThrownBy(() -> incidentService.report(
                IncidentType.OVERSELL, "OVERSELL:SKU-TX", "제목", "상세"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("조회는 트랜잭션 없이도 호출할 수 있다")
    void 조회는_트랜잭션_없이_가능하다() {
        skuService.findAllActive();
        skuService.findBelowThreshold();
    }
}
