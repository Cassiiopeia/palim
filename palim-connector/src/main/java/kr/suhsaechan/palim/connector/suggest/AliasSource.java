package kr.suhsaechan.palim.connector.suggest;

import java.util.List;
import kr.suhsaechan.palim.connector.model.FieldDefinition;
import org.springframework.stereotype.Component;

/**
 * 사전 — 표준 항목에 미리 적어 둔 별칭과 맞춰 본다.
 *
 * <p>가장 확실한 근거라 점수가 가장 높다. 우리가 직접 적은 이름이므로 오탐이 없다.
 *
 * <p>대신 <b>우리가 아는 이름만</b> 잡는다. 다음에 붙일 시스템의 칸 이름은 알 수 없으므로,
 * 이 근거 하나로는 새 원천을 감당하지 못한다. 그래서 다른 근거들과 함께 쓴다.
 */
@Component
public class AliasSource implements SuggestionSource {

    private static final int POINTS = 100;

    @Override
    public Score score(Context context, FieldDefinition candidate) {
        String normalized = context.normalizedField();
        boolean matched = candidate.aliases().stream()
                .anyMatch(alias -> SuggestionSource.normalize(alias).equals(normalized));
        return matched
                ? Score.of(POINTS, "이 이름은 「%s」 항목으로 자주 씁니다".formatted(
                        candidate.displayName()))
                : Score.none();
    }
}
