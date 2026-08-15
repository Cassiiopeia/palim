package kr.suhsaechan.palim.web.reconcile;

import java.math.BigDecimal;
import kr.suhsaechan.palim.reconcile.match.SourceItemBrowser;

/**
 * 담아 둔 품목 하나 — <b>담긴 자료에서 다시 읽은 값</b>과 사람이 넣은 계수.
 *
 * <p>사람이 보낸 품명·수량을 그대로 그리지 않는다. 미리보기가 「무엇을 잇고 있는가」 를
 * 보여주는 자리인데 그 값이 실제와 다르면 확인이 거짓이 된다(07-DECISIONS 032).
 *
 * @param item   담긴 자료에서 다시 읽은 품목
 * @param factor 이 원천의 한 개가 기준 단위로 몇 개인가
 */
public record PickedView(SourceItemBrowser.BrowsedItem item, BigDecimal factor) {

    /**
     * 이 품목이 대조에 더해질 수량.
     *
     * <p>화면이 값을 «바꾸는» 것이 아니라 <b>계수를 넣으면 얼마가 되는지 미리 보여주는</b>
     * 것이다. 계수를 잘못 넣으면 수량이 통째로 어긋나는데, 누르기 전에는 확인할 방법이 없었다.
     */
    public BigDecimal effectiveQuantity() {
        return item.quantity() == null
                ? BigDecimal.ZERO
                : item.quantity().multiply(factor == null ? BigDecimal.ONE : factor);
    }
}
