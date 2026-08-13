package kr.suhsaechan.palim.reconcile.unit;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정합 단위를 만들고 원천 품목을 붙인다.
 *
 * <p>붙이는 일은 <b>두 단계</b>다 — 제안하고, 사람이 확인한다. 자동 제안을 곧바로 반영하면
 * 규칙이 틀렸을 때 엉뚱한 품목을 합쳐 놓고 "재고가 맞는다"고 보고하게 되는데, 이건 불일치를
 * 못 찾는 것보다 나쁘다. 틀렸다는 사실조차 드러나지 않기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class ReconcileUnitService {

    private final ReconcileUnitRepository units;
    private final ReconcileUnitMemberRepository members;

    @Transactional
    public ReconcileUnit create(String code, String name, String baseUnit) {
        units.findByCode(code).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 쓰는 단위 코드입니다: " + code);
        });
        return units.save(ReconcileUnit.of(TenantContext.current(), code, name, baseUnit));
    }

    /**
     * 원천 품목을 단위에 붙일 것을 제안한다.
     *
     * <p>DB 유니크 제약이 최종 방어선이고 이 검사는 <b>사람에게 이유를 알려주는 몫</b>이다.
     * 제약만 있으면 «저장 실패» 만 뜨고 왜인지는 알 수 없다.
     */
    @Transactional
    public ReconcileUnitMember propose(UUID unitId, String source, String itemRef,
                                       BigDecimal factor) {
        units.findById(unitId).orElseThrow(() ->
                new BusinessException(ErrorCode.INVALID_INPUT, "없는 정합 단위입니다."));

        members.findBySourceAndItemRef(source, itemRef).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "이 품목은 이미 다른 단위에 연결되어 있습니다: " + source + " · " + itemRef);
        });

        return members.save(ReconcileUnitMember.of(
                TenantContext.current(), unitId, source, itemRef, factor));
    }

    /** 사람이 확인했다. 이 시점부터 대조 합산에 들어간다. */
    @Transactional
    public ReconcileUnitMember confirm(UUID memberId) {
        ReconcileUnitMember member = members.findById(memberId).orElseThrow(() ->
                new BusinessException(ErrorCode.INVALID_INPUT, "없는 연결입니다."));
        member.confirm();
        return members.save(member);
    }

    /**
     * 연결을 끊는다.
     *
     * <p>제안이든 확정이든 지운다. 잘못 붙인 것을 남겨 두면 그 품목을 다시 붙일 수 없다 —
     * 한 품목은 한 단위에만 속하기 때문이다.
     */
    @Transactional
    public void detach(UUID memberId) {
        members.deleteById(memberId);
    }

    @Transactional(readOnly = true)
    public List<ReconcileUnit> activeUnits() {
        return units.findByIsActiveTrueOrderByCode();
    }

    @Transactional(readOnly = true)
    public List<ReconcileUnitMember> membersOf(UUID unitId) {
        return members.findByUnitIdOrderBySource(unitId);
    }

    /** 아직 확인하지 않은 제안들. 매칭 화면이 이것을 보여준다. */
    @Transactional(readOnly = true)
    public List<ReconcileUnitMember> pending() {
        return members.findByConfirmedAtIsNullOrderBySource();
    }
}
