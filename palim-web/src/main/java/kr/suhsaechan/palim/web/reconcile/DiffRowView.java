package kr.suhsaechan.palim.web.reconcile;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import kr.suhsaechan.palim.reconcile.run.DiffState;
import kr.suhsaechan.palim.reconcile.run.DiffType;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiff;

/**
 * 차이 한 줄을 화면 말로 옮긴다.
 *
 * <p>{@code LEFT_MORE} 를 그대로 보여주면 사람은 «왼쪽이 뭐지» 부터 묻는다. 어느 쪽이 많은지를
 * <b>시스템 이름으로</b> 말해야 무엇을 확인해야 할지 바로 안다.
 *
 * @param unitCode  정합 단위 코드. 미매칭이면 원본 품명이 들어 있다
 * @param leftText  좌측 수량 표기
 * @param rightText 우측 수량 표기
 * @param deltaText 차이 표기. 부호를 살린다
 * @param summary   무슨 일이 있었는지 한 줄
 * @param confirmed 확정된 차이인가. 지금 손댈 것과 지켜볼 것을 가른다
 * @param unmatched 아직 연결되지 않은 품목인가
 */
public record DiffRowView(String unitCode, String leftText, String rightText, String deltaText,
                          String summary, boolean confirmed, boolean unmatched) {

    /** 숫자는 자릿수를 맞춘다. 그래야 눈으로 크기를 비교할 수 있다. */
    private static final DecimalFormat NUMBER = new DecimalFormat("#,##0.###");

    public static DiffRowView of(ReconcileDiff diff, String leftName, String rightName) {
        boolean unmatched = diff.getDiffType() == DiffType.UNMATCHED_LEFT
                || diff.getDiffType() == DiffType.UNMATCHED_RIGHT;

        return new DiffRowView(
                diff.getUnitCode(),
                NUMBER.format(diff.getLeftQuantity()),
                NUMBER.format(diff.getRightQuantity()),
                signed(diff.getDelta()),
                summaryOf(diff, leftName, rightName),
                diff.getState() == DiffState.CONFIRMED,
                unmatched);
    }

    /**
     * 무슨 일이 있었는지 한 문장으로.
     *
     * <p>미매칭은 «차이» 가 아니라 «아직 짝을 못 찾았다» 는 뜻이다. 둘을 같은 말로 적으면
     * 사람이 재고를 맞추려 들지만, 실제로 할 일은 품목을 연결하는 것이다.
     */
    private static String summaryOf(ReconcileDiff diff, String leftName, String rightName) {
        return switch (diff.getDiffType()) {
            case LEFT_MORE -> "%s 쪽이 %s 만큼 많습니다".formatted(
                    leftName, NUMBER.format(diff.getDelta().abs()));
            case RIGHT_MORE -> "%s 쪽이 %s 만큼 많습니다".formatted(
                    rightName, NUMBER.format(diff.getDelta().abs()));
            case UNMATCHED_LEFT -> "%s 에만 있고 아직 어느 품목과도 이어지지 않았습니다"
                    .formatted(leftName);
            case UNMATCHED_RIGHT -> "%s 에만 있고 아직 어느 품목과도 이어지지 않았습니다"
                    .formatted(rightName);
        };
    }

    private static String signed(BigDecimal delta) {
        String text = NUMBER.format(delta.abs());
        return delta.signum() >= 0 ? "+" + text : "-" + text;
    }
}
