package kr.suhsaechan.palim.automation.influencer.scoring;

import java.util.List;

/**
 * 구간 선형 보간.
 *
 * <p>스코어링 임계값을 if 분기로 하드코딩하면 캘리브레이션 때마다 코드를 고쳐야 한다.
 * 곡선을 YAML 제어점 {@code [[x,y],...]} 으로 두고 이 유틸로 계산하면 조정이 설정 변경으로 끝난다.
 */
public final class PiecewiseLinear {

    private PiecewiseLinear() {
    }

    /** curve 는 x 오름차순이어야 한다. 범위 밖 x 는 양 끝 y 로 클램프한다. */
    public static double interpolate(List<List<Double>> curve, double x) {
        if (x <= curve.getFirst().getFirst()) {
            return curve.getFirst().get(1);
        }
        if (x >= curve.getLast().getFirst()) {
            return curve.getLast().get(1);
        }
        for (int i = 1; i < curve.size(); i++) {
            double x1 = curve.get(i).getFirst();
            if (x <= x1) {
                double x0 = curve.get(i - 1).getFirst();
                double y0 = curve.get(i - 1).get(1);
                double y1 = curve.get(i).get(1);
                return y0 + (y1 - y0) * (x - x0) / (x1 - x0);
            }
        }
        return curve.getLast().get(1);
    }
}
