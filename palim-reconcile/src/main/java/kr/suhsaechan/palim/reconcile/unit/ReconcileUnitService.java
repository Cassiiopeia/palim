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

    /**
     * 고른 품목들을 <b>한 물건으로 잇는다</b> — 한 트랜잭션에서.
     *
     * <p>사람이 좌·우 목록에서 담고 미리보기를 본 뒤 누르는 길이다. 담은 것을 눈으로 확인한
     * 뒤이므로 <b>바로 확정</b>한다. 자동 후보에서 한꺼번에 묶는 길과 다른 점이 이것이다 —
     * 저쪽은 표를 훑기만 한 것이라 확인 단계를 한 번 더 거친다.
     *
     * <p><b>이미 어느 물건에 속한 품목이 섞여 있으면 그 물건에 나머지를 붙인다.</b> 그것이
     * 곧 「합치기」 다 — 「전산의 세트 1개 = 물류의 낱개 3개」 처럼 한쪽이 여럿인 경우가
     * 이 길로 풀린다. 새 표도 새 개념도 필요 없다.
     *
     * <p>서로 <b>다른</b> 두 물건에 속한 품목이 함께 담기면 막는다. 그것은 「두 물건을
     * 합치는」 일인데, 어느 이름을 남길지·수량을 어떻게 볼지가 사람의 판단이라 조용히
     * 정해 버리면 안 된다.
     *
     * @param picks   담은 품목들. (원천, 품목코드, 계수)
     * @param newCode 새로 만들 때 쓸 코드. 기존 물건에 붙는 경우엔 쓰이지 않는다
     * @param newName 새로 만들 때 쓸 이름
     * @return 이어 붙인 물건
     */
    @Transactional
    public ReconcileUnit link(List<Pick> picks, String newCode, String newName, String baseUnit) {
        if (picks == null || picks.isEmpty()) {
            throw new BusinessException(ErrorCode.RECONCILE_LINK_EMPTY);
        }

        // 이미 어느 물건에 속해 있는가. 담긴 것 기준으로 본다.
        List<UUID> existingUnits = picks.stream()
                .map(pick -> members.findBySourceAndItemRef(pick.source(), pick.itemRef()))
                .flatMap(java.util.Optional::stream)
                .map(ReconcileUnitMember::getUnitId)
                .distinct()
                .toList();

        if (existingUnits.size() > 1) {
            throw new BusinessException(ErrorCode.RECONCILE_LINK_TWO_UNITS);
        }

        ReconcileUnit unit = existingUnits.isEmpty()
                ? create(newCode, newName, baseUnit)
                : units.findById(existingUnits.getFirst()).orElseThrow(() ->
                        new BusinessException(ErrorCode.INVALID_INPUT, "없는 정합 단위입니다."));

        for (Pick pick : picks) {
            // 이미 그 물건에 붙어 있는 것은 건너뛴다 — 「더 담기」 는 나머지만 붙이는 일이다.
            if (members.findBySourceAndItemRef(pick.source(), pick.itemRef()).isPresent()) {
                continue;
            }
            ReconcileUnitMember member = members.save(ReconcileUnitMember.of(
                    TenantContext.current(), unit.getId(),
                    pick.source(), pick.itemRef(), pick.factor()));
            // 눈으로 보고 누른 길이므로 바로 확정한다.
            member.confirm();
            members.save(member);
        }
        return unit;
    }

    /**
     * 담은 품목 하나.
     *
     * @param factor 이 원천의 한 개가 <b>기준 단위로 몇 개</b>인가. 박스 하나가 12개면 12.
     *               적재 단계의 단위 환산과 다른 값이다 — 그쪽은 원천 수량을 기준 수량으로
     *               바꾸고, 이것은 <b>이미 기준 수량이 된 값</b>에 곱한다
     */
    public record Pick(String source, String itemRef, BigDecimal factor) {
    }

    /** 이 품목의 계수를 고친다. 잘못 넣으면 수량이 통째로 어긋나므로 고칠 길이 있어야 한다. */
    @Transactional
    public ReconcileUnitMember changeFactor(UUID memberId, BigDecimal factor) {
        ReconcileUnitMember member = members.findById(memberId).orElseThrow(() ->
                new BusinessException(ErrorCode.INVALID_INPUT, "없는 연결입니다."));
        member.changeFactor(factor);
        return members.save(member);
    }

    /**
     * 멤버 하나를 확정한다 — <b>화면에서 부르지 않는다.</b>
     *
     * <p>한 멤버만 확정하면 반쪽짜리 단위가 되고, 대조가 매일 유령 차이를 올린다(아래
     * {@link #confirmUnit} 참고). 화면은 반드시 단위 단위로 확인한다.
     *
     * <p>이 메서드가 남아 있는 이유는 <b>시험이 「확정된 멤버」 상태를 만들 때</b> 쓰기
     * 때문이다. 그때는 양쪽을 함께 확정하므로 반쪽이 되지 않는다.
     */
    @Transactional
    public ReconcileUnitMember confirm(UUID memberId) {
        ReconcileUnitMember member = members.findById(memberId).orElseThrow(() ->
                new BusinessException(ErrorCode.INVALID_INPUT, "없는 연결입니다."));
        member.confirm();
        return members.save(member);
    }

    /**
     * 사람이 확인했다 — <b>이 단위를 통째로.</b>
     *
     * <p>한 멤버만 확정하면 <b>반쪽짜리 단위</b>가 된다. 합산은 확인된 멤버만 더하므로
     * 좌·우 두 품목짜리 단위에서 좌만 확정하면 「좌 120 · 우 0」 이 되고, 대조는 매일
     * 「전산이 많음」 을 올리며 이튿날 확정으로 승격시켜 알림까지 보낸다.
     *
     * <p><b>사람은 그것을 매칭 문제가 아니라 재고 사고로 읽는다.</b> 「잘 이어 놨는데 왜 매일
     * 차이가 나지」 로 만나고, 원인이 여기 있다는 것을 알 방법이 없다.
     *
     * <p>확인의 뜻은 「이 묶음이 같은 물건임을 사람이 봤다」 이지 「이 한 줄을 봤다」 가 아니다.
     * 그래서 단위 단위로 확정한다.
     *
     * @return 이번에 확정된 멤버들. 이미 다 확정돼 있었으면 빈 목록
     */
    @Transactional
    public List<ReconcileUnitMember> confirmUnit(UUID unitId) {
        List<ReconcileUnitMember> pending = members.findByUnitIdAndConfirmedAtIsNull(unitId);
        pending.forEach(ReconcileUnitMember::confirm);
        return members.saveAll(pending);
    }

    /**
     * 확인 대기 중인 물건을 <b>통째로 물린다.</b>
     *
     * <p>확인 큐의 한 줄은 물건 하나이므로 「아닙니다」 도 그 단위로 움직여야 한다. 한 줄만
     * 떼면 반쪽이 남아 무엇을 물린 것인지 알 수 없게 된다.
     *
     * <p><b>확정된 멤버는 건드리지 않는다.</b> 이미 사람이 확인한 것까지 지우면, 나중에 붙인
     * 제안 하나를 물렸다가 멀쩡히 돌던 연결이 함께 사라진다. 그러면 대조에서 그 물건의 재고가
     * 통째로 증발한다.
     *
     * <p>남은 멤버가 없으면 물건도 함께 접는다 — 빈 물건이 목록에 쌓이면 무엇이 진짜인지
     * 흐려진다.
     */
    @Transactional
    public void discardPending(UUID unitId) {
        members.deleteAll(members.findByUnitIdAndConfirmedAtIsNull(unitId));
        if (members.findByUnitIdOrderBySource(unitId).isEmpty()) {
            units.findById(unitId).ifPresent(unit -> {
                unit.deactivate();
                units.save(unit);
            });
        }
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

    /**
     * 이 물건을 <b>통째로 푼다</b> — 든 품목을 전부 떼고 물건을 접는다.
     *
     * <p>표의 한 줄이 물건 하나이므로 「되돌리기」 도 줄 단위여야 한다. 한 품목만 떼면 나머지가
     * 반쪽으로 남아 대조가 매일 유령 차이를 올리는데, 사람은 그것을 되돌리다 만 흔적이 아니라
     * <b>재고 사고로 읽는다.</b>
     *
     * <p>떼어 낸 품목들은 다시 「이을 수 있는 것」 으로 돌아온다.
     */
    @Transactional
    public void unlinkUnit(UUID unitId) {
        members.deleteAll(members.findByUnitIdOrderBySource(unitId));
        units.findById(unitId).ifPresent(unit -> {
            unit.deactivate();
            units.save(unit);
        });
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
