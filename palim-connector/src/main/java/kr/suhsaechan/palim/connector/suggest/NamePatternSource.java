package kr.suhsaechan.palim.connector.suggest;

import java.util.List;
import kr.suhsaechan.palim.connector.model.FieldDefinition;
import org.springframework.stereotype.Component;

/**
 * 이름 규칙 — 칸 이름에 <b>든 낱말</b>로 짐작한다.
 *
 * <p>사전이 못 잡는 이름을 건진다. {@code CURRENT_STOCK_QUANTITY} 는 우리 사전에 없지만
 * {@code QUANTITY} 가 들어 있으므로 수량으로 볼 만하다.
 *
 * <p>정확하지는 않다 — {@code ORDER_QTY}(주문 수량)도 걸린다. 그래서 사전보다 점수가 낮고,
 * 이 근거만으로는 임계를 넘지 못하게 두었다. 다른 근거가 함께 가리켜야 채워진다.
 */
@Component
public class NamePatternSource implements SuggestionSource {

    private static final int POINTS = 60;

    @Override
    public Score score(Context context, FieldDefinition candidate) {
        String normalized = context.normalizedField();
        // 별칭에서 낱말을 빌려 온다. 별도 규칙표를 두면 항목을 늘릴 때 두 곳을 고쳐야 한다.
        boolean contains = candidate.aliases().stream()
                .map(SuggestionSource::normalize)
                .filter(alias -> alias.length() >= 3)
                .anyMatch(alias -> normalized.contains(alias) || alias.contains(normalized));
        return contains
                ? Score.of(POINTS, "이름에 「%s」 와 통하는 낱말이 있습니다".formatted(
                        candidate.displayName()))
                : Score.none();
    }
}
