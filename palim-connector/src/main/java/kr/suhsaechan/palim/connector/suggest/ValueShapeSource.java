package kr.suhsaechan.palim.connector.suggest;

import java.util.List;
import kr.suhsaechan.palim.connector.model.FieldDataType;
import kr.suhsaechan.palim.connector.model.FieldDefinition;
import org.springframework.stereotype.Component;

/**
 * 값 모양 — 칸에 <b>실제로 들어 있는 값</b>으로 짐작한다.
 *
 * <p>이름이 {@code COL_017} 처럼 아무 뜻 없는 경우가 실제로 있다. 그때 이름으로는 아무것도 못
 * 하지만, 값이 {@code 112 · 9,451 · 4} 면 수량 후보이고 {@code 2026-08-13} 이면 날짜다.
 *
 * <p>다만 값만으로는 <b>수량인지 단가인지 구분하지 못한다.</b> 둘 다 숫자다. 그래서 점수가 낮고
 * 단독으로는 임계를 넘지 못한다 — 다른 근거를 보태는 용도다.
 */
@Component
public class ValueShapeSource implements SuggestionSource {

    private static final int POINTS = 40;

    @Override
    public Score score(Context context, FieldDefinition candidate) {
        List<String> samples = context.samples();
        if (samples.isEmpty()) {
            return Score.none();
        }
        return switch (candidate.dataType()) {
            case DECIMAL, INTEGER -> allNumeric(samples)
                    ? Score.of(POINTS, "값이 모두 숫자입니다")
                    : Score.none();
            case DATE, TIMESTAMP -> allDateLike(samples)
                    ? Score.of(POINTS, "값이 모두 날짜 모양입니다")
                    : Score.none();
            default -> Score.none();
        };
    }

    /**
     * 전부 맞아야 인정한다. 하나만 달라도 아니다 — 절반이 숫자인 칸을 수량으로 골라 주면
     * 나머지 절반은 적재에서 실패하고, 그 실패를 사람이 뒤늦게 확인하게 된다.
     */
    private static boolean allNumeric(List<String> samples) {
        return samples.stream().allMatch(value -> {
            String cleaned = value.replaceAll("[,\\s]", "");
            if (cleaned.isEmpty()) {
                return false;
            }
            try {
                Double.parseDouble(cleaned);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        });
    }

    /** {@code 2026-08-13} 과 {@code 20260813} 둘 다 흔하다. 구분자 없는 표기가 특히 잦다. */
    private static boolean allDateLike(List<String> samples) {
        return samples.stream().allMatch(value -> {
            String cleaned = value.trim();
            return cleaned.matches("\\d{4}-\\d{2}-\\d{2}.*") || cleaned.matches("\\d{8}");
        });
    }
}
