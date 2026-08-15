package kr.suhsaechan.palim.web.reconcile;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 확인을 기다리는 <b>물건 하나</b> — 그 안에 든 품목들과 함께.
 *
 * <p>예전 확인 화면은 <b>멤버 한 줄씩</b> 보여줬고, 그 줄에 있는 것은 원천 이름·품목코드·계수
 * 뿐이었다. 「erp · A0001 · 1」 을 보고 맞다/아니다를 판단하는 것은 <b>물리적으로 불가능하다</b> —
 * 그것이 무슨 물건인지 알 수 없고, 무엇과 묶였는지도 안 보인다. 판단할 수 없는 확인 단계는
 * 확인이 아니다.
 *
 * <p>그리고 한 줄씩 확정하면 <b>반쪽짜리 물건</b>이 생긴다. 좌·우 두 품목 중 좌만 확정하면
 * 합산이 「좌 120 · 우 0」 이 되어 대조가 매일 「전산이 많음」 을 올리고, 사람은 그것을 매칭
 * 문제가 아니라 재고 사고로 읽는다.
 *
 * @param unitId   확인 버튼이 가리킬 물건
 * @param unitName 사람이 부르는 이름
 * @param members  그 물건에 든 품목들. 양쪽이 나란히 보여야 같은 물건인지 판단할 수 있다
 */
public record PendingUnitView(UUID unitId, String unitName, List<Member> members) {

    /** 한쪽에서만 온 묶음. 짝이 없으면 사람이 더 살펴봐야 한다. */
    public boolean oneSided() {
        return members.stream().map(Member::source).distinct().count() < 2;
    }

    /** 지금 담긴 재고에 없는 품목이 섞여 있다. 사람이 알아야 판단이 선다. */
    public boolean hasMissing() {
        return members.stream().anyMatch(member -> !member.inStock());
    }

    /**
     * 확인 큐에 보이는 품목 하나.
     *
     * <p><b>담긴 재고에 없어도 지우지 않는다.</b> 없다고 줄을 빼면 그 물건은 확인도 물리기도
     * 못 하는 상태로 화면에서 사라진다 — 큐에는 남아 있는데 손댈 자리가 없는, 막다른 길이다.
     * 원천이 품목코드를 바꾸거나 그날 재고가 0이면 실제로 그렇게 된다.
     *
     * @param inStock 지금 담긴 재고에 있는가. 없으면 품명·수량을 알 방법이 없다
     */
    public record Member(String source, String itemRef, String displayName,
                         BigDecimal quantity, BigDecimal factor, boolean inStock) {

        /** 품명을 모르면 코드라도 보여준다 — 빈 칸보다 낫다. */
        public String label() {
            return displayName == null || displayName.isBlank() ? itemRef : displayName;
        }
    }
}
