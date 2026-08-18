package kr.suhsaechan.palim.web.reconcile;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.UUID;
import kr.suhsaechan.palim.reconcile.run.DiffState;
import kr.suhsaechan.palim.reconcile.run.DiffType;
import kr.suhsaechan.palim.reconcile.run.ReconcileDiff;

/**
 * 차이 한 줄을 화면 말로 옮긴다.
 *
 * <p>{@code LEFT_MORE} 를 그대로 보여주면 사람은 «왼쪽이 뭐지» 부터 묻는다. 어느 쪽이 많은지를
 * <b>시스템 이름으로</b> 말해야 무엇을 확인해야 할지 바로 안다.
 *
 * <p><b>이름을 코드보다 앞세운다.</b> 예전에는 「U-6668d23b · +11」 이라고만 떴다. 그것이 무슨
 * 물건인지 알 수 없으니 손댈 수가 없고, 알 수 없는 줄은 결국 안 보게 된다. 이름은 물건에 이미
 * 있었는데 화면이 코드를 그리고 있었다.
 *
 * <p>{@code leftParts}·{@code rightParts} 는 <b>몇 개가 합쳐진 값인지</b>를 말한다. 「4↔4건」
 * 과 「1↔2건」 은 전혀 다른 상태인데, 합계만 보면 둘 다 그냥 숫자 하나다.
 *
 * @param unitId     펼쳐서 뜯어볼 물건. 미매칭이면 비어 있다
 * @param unitName   사람이 부르는 이름. 없으면 코드로 대신한다
 * @param unitCode   시스템이 쓰는 코드. 이름 옆에 작게 둔다
 * @param leftParts  왼쪽에서 합쳐진 품목 수
 * @param rightParts 오른쪽에서 합쳐진 품목 수
 * @param summary    무슨 일이 있었는지 한 줄
 * @param confirmed  확정된 차이인가. 지금 손댈 것과 지켜볼 것을 가른다
 * @param unmatched  아직 연결되지 않은 품목인가
 */
public record DiffRowView(UUID diffId, UUID unitId, String unitName, String unitCode,
                          int leftParts, int rightParts,
                          String leftText, String rightText, String deltaText,
                          String summary, boolean confirmed, boolean unmatched) {

    /** 숫자는 자릿수를 맞춘다. 그래야 눈으로 크기를 비교할 수 있다. */
    private static final DecimalFormat NUMBER = new DecimalFormat("#,##0.###");

    public static DiffRowView of(ReconcileDiff diff, String leftName, String rightName,
                                 String unitName, int leftParts, int rightParts) {
        boolean unmatched = diff.getDiffType() == DiffType.UNMATCHED_LEFT
                || diff.getDiffType() == DiffType.UNMATCHED_RIGHT;

        return new DiffRowView(
                diff.getId(),
                diff.getUnitId(),
                unitName == null || unitName.isBlank() ? diff.getUnitCode() : unitName,
                diff.getUnitCode(),
                leftParts,
                rightParts,
                NUMBER.format(diff.getLeftQuantity()),
                NUMBER.format(diff.getRightQuantity()),
                signed(diff.getDelta()),
                summaryOf(diff, leftName, rightName),
                diff.getState() == DiffState.CONFIRMED,
                unmatched);
    }

    /** 여러 품목이 합쳐진 줄인가. 그렇다면 합계만 봐서는 무슨 일인지 알 수 없다. */
    public boolean composite() {
        return leftParts > 1 || rightParts > 1;
    }

    /**
     * 좌·우에 든 품목 수가 어긋나나.
     *
     * <p>「1↔2건」 은 한쪽에 품목이 하나 더 붙어 있다는 뜻이고, 대개 <b>잘못 이어 둔 것</b>이다.
     * 합계만 보면 그냥 차이 나는 줄로 보인다.
     */
    public boolean lopsided() {
        return !unmatched && leftParts != rightParts;
    }

    /** 「4↔4건」 처럼 몇 개가 합쳐졌는지. */
    public String partsText() {
        return leftParts + "↔" + rightParts + "건";
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
