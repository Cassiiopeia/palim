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
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 쓰는 묶음 코드입니다: " + code);
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
                new BusinessException(ErrorCode.INVALID_INPUT, "없는 묶음입니다."));

        members.findBySourceAndItemRef(source, itemRef).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "이 품목은 이미 다른 묶음에 들어 있습니다: " + source + " · " + itemRef);
        });

        return members.save(ReconcileUnitMember.of(
                TenantContext.current(), unitId, source, itemRef, factor));
    }

    /**
     * 고른 품목들을 <b>한 묶음으로 잇는다</b> — 한 트랜잭션에서.
     *
     * <p>사람이 좌·우 목록에서 담고 미리보기를 본 뒤 누르는 길이다. 담은 것을 눈으로 확인한
     * 뒤이므로 <b>바로 확정</b>한다. 자동 후보에서 한꺼번에 묶는 길과 다른 점이 이것이다 —
     * 저쪽은 표를 훑기만 한 것이라 확인 단계를 한 번 더 거친다.
     *
     * <p><b>이미 어느 묶음에 속한 품목이 섞여 있으면 그 묶음에 나머지를 붙인다.</b> 그것이
     * 곧 「합치기」 다 — 「전산의 세트 1개 = 물류의 낱개 3개」 처럼 한쪽이 여럿인 경우가
     * 이 길로 풀린다. 새 표도 새 개념도 필요 없다.
     *
     * <p>서로 <b>다른</b> 두 묶음에 속한 품목이 함께 담기면 막는다. 그것은 「두 묶음을
     * 합치는」 일인데, 어느 이름을 남길지·수량을 어떻게 볼지가 사람의 판단이라 조용히
     * 정해 버리면 안 된다.
     *
     * @param picks   담은 품목들. (원천, 품목코드, 계수)
     * @param newCode 새로 만들 때 쓸 코드. 기존 묶음에 붙는 경우엔 쓰이지 않는다
     * @param newName 새로 만들 때 쓸 이름
     * @return 이어 붙인 묶음
     */
    @Transactional
    public ReconcileUnit link(List<Pick> picks, String newCode, String newName, String baseUnit) {
        if (picks == null || picks.isEmpty()) {
            throw new BusinessException(ErrorCode.RECONCILE_LINK_EMPTY);
        }

        // 이미 어느 묶음에 속해 있는가. 담긴 것 기준으로 본다.
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
                        new BusinessException(ErrorCode.INVALID_INPUT, "없는 묶음입니다."));

        for (Pick pick : picks) {
            // 이미 그 묶음에 붙어 있는 것은 건너뛴다 — 「더 담기」 는 나머지만 붙이는 일이다.
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

    /**
     * 묶음 이름을 고친다.
     *
     * <p>이름은 <b>사람이 보는 유일한 손잡이</b>다. 대조 결과에 「U-6668d23b · +11」 이라고만
     * 뜨면 그것이 무슨 묶음인지 알 수 없고, 알 수 없는 줄은 손대지 않게 된다.
     *
     * <p>코드는 안 바꾼다 — 코드는 시스템이 쓰는 값이고, 겹치면 저장이 막힌다.
     */
    @Transactional
    public ReconcileUnit rename(UUID unitId, String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이름을 비울 수 없습니다.");
        }
        ReconcileUnit unit = units.findById(unitId).orElseThrow(() ->
                new BusinessException(ErrorCode.INVALID_INPUT, "없는 묶음입니다."));
        unit.rename(name.trim());
        return units.save(unit);
    }

    /** 이 품목의 계수를 고친다. 잘못 넣으면 수량이 통째로 어긋나므로 고칠 길이 있어야 한다. */
    @Transactional
    public ReconcileUnitMember changeFactor(UUID memberId, BigDecimal factor) {
        ReconcileUnitMember member = members.findById(memberId).orElseThrow(() ->
                new BusinessException(ErrorCode.INVALID_INPUT, "없는 품목 연결입니다."));
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
                new BusinessException(ErrorCode.INVALID_INPUT, "없는 품목 연결입니다."));
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
     * <p>확인의 뜻은 「이 묶음이 같은 묶음임을 사람이 봤다」 이지 「이 한 줄을 봤다」 가 아니다.
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
     * 확인 대기 중인 묶음을 <b>통째로 물린다.</b>
     *
     * <p>확인 큐의 한 줄은 묶음 하나이므로 「아닙니다」 도 그 단위로 움직여야 한다. 한 줄만
     * 떼면 반쪽이 남아 무엇을 물린 것인지 알 수 없게 된다.
     *
     * <p><b>확정된 멤버는 건드리지 않는다.</b> 이미 사람이 확인한 것까지 지우면, 나중에 붙인
     * 제안 하나를 물렸다가 멀쩡히 돌던 연결이 함께 사라진다. 그러면 대조에서 그 묶음의 재고가
     * 통째로 증발한다.
     *
     * <p>남은 멤버가 없으면 묶음도 함께 접는다 — 빈 묶음이 목록에 쌓이면 무엇이 진짜인지
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
     * 품목 하나를 <b>이 묶음에</b> 붙인다.
     *
     * <p>{@link #link} 는 「담은 것들 중 이미 묶인 것이 있으면 그 묶음에 붙인다」 라 <b>고른
     * 품목이 자유로우면 새 묶음을 만들어 버린다.</b> 편집 화면은 붙일 묶음이 이미 정해져
     * 있으므로 그 길을 쓰면 엉뚱한 묶음이 하나 더 생긴다.
     */
    @Transactional
    public ReconcileUnitMember attach(UUID unitId, String source, String itemRef,
                                      BigDecimal factor) {
        ReconcileUnitMember member = propose(unitId, source, itemRef, factor);
        // 편집 화면에서 눈으로 보고 고른 것이라 바로 확정한다.
        member.confirm();
        return members.save(member);
    }

    /**
     * 두 묶음을 <b>하나로 합친다.</b>
     *
     * <p>이 길이 없어서 「이미 서로 다른 묶음에 속한 품목입니다」 로 <b>막히기만 했다.</b>
     * 막은 이유는 있었다 — 어느 이름을 남길지가 사람의 판단이라 조용히 정하면 안 된다. 그런데
     * <b>막기만 하고 할 길을 안 주면</b> 그건 그냥 못 하는 일이 된다. 나눠 묶어 놓고 보니
     * 하나로 봐야 하더라는 것은 실제로 늘 생긴다.
     *
     * <p>그래서 <b>어느 이름을 남길지 사람이 고른 뒤</b> 합친다. 남는 쪽이 목표 묶음이고,
     * 다른 쪽 품목이 그리로 옮겨 온 뒤 빈 묶음은 접힌다.
     */
    @Transactional
    public ReconcileUnit merge(UUID targetUnitId, UUID sourceUnitId) {
        if (targetUnitId.equals(sourceUnitId)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "같은 묶음끼리는 합칠 수 없습니다.");
        }
        ReconcileUnit target = units.findById(targetUnitId).orElseThrow(() ->
                new BusinessException(ErrorCode.INVALID_INPUT, "없는 묶음입니다."));
        ReconcileUnit source = units.findById(sourceUnitId).orElseThrow(() ->
                new BusinessException(ErrorCode.INVALID_INPUT, "없는 묶음입니다."));

        for (ReconcileUnitMember member : members.findByUnitIdOrderBySource(source.getId())) {
            member.moveTo(target.getId());
            members.save(member);
        }
        source.deactivate();
        units.save(source);
        return target;
    }

    /**
     * 이 묶음을 <b>여러 묶음으로 쪼갠다.</b>
     *
     * <p>자동 후보는 이름이 닮은 것을 <b>통째로 하나</b>로 묶는다. 그런데 로트 셋이 든 묶음은
     * 「+50 하나」 로 볼 수도 있고 「+24 · +26 · 맞음」 셋으로 볼 수도 있다 — <b>어느 쪽이 맞는
     * 운영인지는 회사가 정할 일</b>이다. 쪼갤 길이 없으면 코드가 정해 버린 셈이 된다.
     *
     * <p>각 무리가 새 묶음이 되고, 원래 묶음은 남는 품목이 없으면 접힌다. 무리가 하나뿐이면
     * 쪼갤 것이 없으므로 아무 일도 하지 않는다.
     *
     * @param groups 무리마다 (그 묶음 이름, 그 묶음에 넣을 품목들)
     * @return 새로 생긴 묶음들
     */
    @Transactional
    public List<ReconcileUnit> split(UUID unitId, List<Group> groups) {
        if (groups == null || groups.size() < 2) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "둘 이상으로 갈라야 쪼개집니다.");
        }
        ReconcileUnit origin = units.findById(unitId).orElseThrow(() ->
                new BusinessException(ErrorCode.INVALID_INPUT, "없는 묶음입니다."));

        List<ReconcileUnit> created = new java.util.ArrayList<>();
        for (Group group : groups) {
            ReconcileUnit fresh = create(newCode(), group.name(), origin.getBaseUnit());
            for (Member member : group.members()) {
                members.findBySourceAndItemRef(member.source(), member.itemRef())
                        .ifPresent(existing -> {
                            existing.moveTo(fresh.getId());
                            members.save(existing);
                        });
            }
            created.add(fresh);
        }

        // 어느 무리에도 안 들어간 품목이 남으면 원래 묶음이 그대로 남는다 — 그것도 사실이므로
        // 지우지 않는다. 다 옮겨 갔으면 빈 묶음이 목록에 쌓이지 않게 접는다.
        if (members.findByUnitIdOrderBySource(unitId).isEmpty()) {
            origin.deactivate();
            units.save(origin);
        }
        return created;
    }

    /** 쪼갤 때 만들 묶음 하나. */
    public record Group(String name, List<Member> members) {
    }

    /** 품목 하나를 가리키는 값. */
    public record Member(String source, String itemRef) {
    }

    /** 코드는 사람에게 묻지 않는다 — 사람이 신경 쓸 값이 아니고, 겹치면 저장이 막힌다. */
    private String newCode() {
        return "U-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 이 묶음을 <b>통째로 푼다</b> — 든 품목을 전부 떼고 묶음을 접는다.
     *
     * <p>표의 한 줄이 묶음 하나이므로 「되돌리기」 도 줄 단위여야 한다. 한 품목만 떼면 나머지가
     * 반쪽으로 남아 대조가 매일 유령 차이를 올리는데, 사람은 그것을 되돌리다 만 흔적이 아니라
     * <b>재고 사고로 읽는다.</b>
     *
     * <p>떼어 낸 품목들은 다시 「묶을 수 있는 것」 으로 돌아온다.
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
