package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnit;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitMember;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitMemberRepository;
import kr.suhsaechan.palim.reconcile.unit.ReconcileUnitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 같은 물건을 원천마다 다르게 세는 문제를 사람이 정리한다.
 *
 * <p>전산은 「1박스」로, 물류는 「낱개 12개」로 센다. 품목 코드 체계도 서로 무관해서 코드로는
 * 맞출 수 없다. 그래서 <b>무엇을 하나로 볼지</b> 를 사람이 정하고, 시스템은 그 정의대로 센다.
 */
class ReconcileUnitIntegrationTest extends IntegrationTest {

    @Autowired private ReconcileUnitService unitService;
    @Autowired private ReconcileUnitMemberRepository memberRepository;

    private String code() {
        return "UNIT-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private String item() {
        return "ITEM-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 자동 제안을 곧바로 반영하면 규칙이 틀렸을 때 엉뚱한 품목을 합쳐 놓고 «재고가 맞는다» 고
     * 보고한다. 불일치를 못 찾는 것보다 나쁘다 — 틀렸다는 사실조차 드러나지 않는다.
     */
    @Test
    @DisplayName("확인하기 전에는 제안 상태로만 남는다")
    void 제안은_확인_전까지_반영되지_않는다() {
        ReconcileUnit unit = unitService.create(code(), "제품A 227g", "EA");
        ReconcileUnitMember member = unitService.propose(
                unit.getId(), "erp", item(), BigDecimal.ONE);

        assertThat(member.isConfirmed())
                .as("사람이 확인하지 않은 추측으로 재고를 합산하면 결과가 맞는지 아무도 모른다")
                .isFalse();

        unitService.confirm(member.getId());

        assertThat(memberRepository.findById(member.getId()).orElseThrow().isConfirmed())
                .isTrue();
    }

    /**
     * 한 품목이 두 단위에 붙으면 그 수량이 두 번 세어지고 대조 결과가 조용히 틀린다.
     * 결과가 «틀렸다» 가 아니라 «맞는 것처럼 보인다» 는 것이 문제다.
     */
    @Test
    @DisplayName("한 품목을 두 단위에 붙이면 거부한다")
    void 품목은_한_단위에만_속한다() {
        ReconcileUnit first = unitService.create(code(), "제품A", "EA");
        ReconcileUnit second = unitService.create(code(), "제품B", "EA");
        String itemRef = item();
        unitService.propose(first.getId(), "erp", itemRef, BigDecimal.ONE);

        assertThatThrownBy(() ->
                unitService.propose(second.getId(), "erp", itemRef, BigDecimal.ONE))
                .as("두 단위에 붙으면 수량이 두 번 세어져 결과가 조용히 틀린다")
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 세트 상품을 별도 기능으로 만들지 않는 이유. 「1세트 = 낱개 12」와 「전산 1 = 물류 3」이
     * 같은 구조가 된다.
     */
    @Test
    @DisplayName("환산 계수로 세는 단위 차이를 흡수한다")
    void 환산_계수로_단위_차이를_흡수한다() {
        ReconcileUnit unit = unitService.create(code(), "제품A 12입", "BOX");

        ReconcileUnitMember erp = unitService.propose(
                unit.getId(), "erp", item(), BigDecimal.ONE);
        ReconcileUnitMember wms = unitService.propose(
                unit.getId(), "wms", item(), new BigDecimal("0.0833333"));

        assertThat(erp.getFactor()).isEqualByComparingTo("1");
        assertThat(wms.getFactor())
                .as("낱개 12개가 1박스가 된다")
                .isEqualByComparingTo("0.0833333");
    }

    /**
     * 잘못 붙인 것을 남겨 두면 그 품목을 다시 붙일 수 없다 — 한 품목은 한 단위에만 속하므로
     * 끊는 길이 없으면 막다른 길이 된다.
     */
    @Test
    @DisplayName("잘못 붙였으면 끊고 다시 붙일 수 있다")
    void 끊고_다시_붙인다() {
        ReconcileUnit first = unitService.create(code(), "제품A", "EA");
        ReconcileUnit second = unitService.create(code(), "제품B", "EA");
        String itemRef = item();

        ReconcileUnitMember wrong = unitService.propose(
                first.getId(), "erp", itemRef, BigDecimal.ONE);
        unitService.detach(wrong.getId());

        ReconcileUnitMember fixed = unitService.propose(
                second.getId(), "erp", itemRef, BigDecimal.ONE);

        assertThat(fixed.getUnitId()).isEqualTo(second.getId());
    }

    @Test
    @DisplayName("확인하지 않은 제안만 따로 볼 수 있다")
    void 확인할_것을_모아_본다() {
        ReconcileUnit unit = unitService.create(code(), "제품A", "EA");
        ReconcileUnitMember pending = unitService.propose(
                unit.getId(), "wms", item(), BigDecimal.ONE);
        ReconcileUnitMember done = unitService.propose(
                unit.getId(), "erp", item(), BigDecimal.ONE);
        unitService.confirm(done.getId());

        assertThat(unitService.pending())
                .extracting(ReconcileUnitMember::getId)
                .contains(pending.getId())
                .doesNotContain(done.getId());
    }
}
