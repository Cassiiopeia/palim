package kr.suhsaechan.palim.notification.payload;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 두 시스템의 재고가 어긋났다는 알림 내용.
 *
 * <p>{@link StockMismatchPayload} 와 헷갈리기 쉬운데 <b>다른 사건이다.</b> 저쪽은 한 시스템
 * 안에서 기준값과 변동 이력이 안 맞는 것이고, 이것은 서로 다른 두 곳의 수량이 안 맞는 것이다.
 *
 * <p>둘이 같은 알림 종류를 쓰고 있었다. 그래서 대조가 보낸 내용을 받는 쪽이 저쪽 형식으로
 * 읽었고, 칸 이름이 하나도 안 맞아 <b>「재고 수량 0개, 이력 누적합 0개」 라는 빈 알림</b>이
 * 나갔다. 오류가 아니라 «성공한 것처럼 보이는 빈 값» 이라 아무도 이상하다고 여기지 않는다.
 *
 * @param definition  대조 이름. 어느 대조인지 모르면 어디를 봐야 할지 알 수 없다
 * @param leftSource  전산 쪽 원천 이름
 * @param rightSource 실물 쪽 원천 이름
 * @param baseAt      견준 시점. <b>표시 직전에만</b> 지역 시각으로 바꾼다
 * @param count       알릴 만한 차이 건수. 본문에 담은 것보다 많을 수 있다
 * @param samples     그중 몇 개. 전부 넣으면 알림이 길어져 아무도 안 읽는다
 */
public record ReconcileMismatchPayload(
        String definition,
        String leftSource,
        String rightSource,
        Instant baseAt,
        int count,
        List<Sample> samples
) {

    /**
     * 차이 하나.
     *
     * @param unit  정합 단위 코드
     * @param left  전산 쪽 수량
     * @param right 실물 쪽 수량
     * @param delta 차이. 부호가 어느 쪽이 많은지를 말한다
     */
    public record Sample(String unit, BigDecimal left, BigDecimal right, BigDecimal delta) {
    }
}
