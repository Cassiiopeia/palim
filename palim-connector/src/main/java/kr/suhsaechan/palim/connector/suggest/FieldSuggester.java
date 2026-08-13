package kr.suhsaechan.palim.connector.suggest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.suhsaechan.palim.connector.model.FieldDefinition;
import kr.suhsaechan.palim.connector.model.StandardModelFields;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 원천 칸을 어느 표준 항목에 연결할지 <b>미리 골라 둔다</b>.
 *
 * <p>여러 근거({@link SuggestionSource})에 점수를 매겨 합산하고, 가장 높은 항목을 고른다.
 * 하나의 근거만 쓰면 반드시 틀린다 — 사전은 아는 이름만 잡고, 이름 규칙은 오탐하며, 값 모양은
 * 수량과 단가를 구분하지 못한다.
 *
 * <p><b>추천일 뿐 확정이 아니다.</b> 화면은 이 결과를 미리 골라진 상태로 보여주고 사람이 고친다.
 * 그래서 근거가 약하면 찍지 않고 비워 둔다 — 사람은 빈 칸은 채우지만, 그럴듯하게 채워진 칸은
 * 확인하지 않고 넘어간다.
 */
@Component
@RequiredArgsConstructor
public class FieldSuggester {

    /**
     * 이 점수를 넘어야 채운다.
     *
     * <p>이름 규칙(60)이나 값 모양(40) <b>하나만</b>으로는 넘지 못하도록 잡았다. 둘이 함께
     * 가리키거나, 사전·기록처럼 확실한 근거가 있어야 한다.
     */
    private static final int THRESHOLD = 80;

    private final List<StandardModelFields> models;
    private final List<SuggestionSource> sources;

    /**
     * @param sourceFields 원천이 준 칸 이름들
     * @param sampleRows   그 칸들에 실제로 들어 있는 값 몇 행
     * @param targetModel  담을 표준 모델 코드
     */
    public List<FieldSuggestion> suggest(List<String> sourceFields,
                                         List<Map<String, Object>> sampleRows,
                                         String targetModel) {
        List<FieldDefinition> candidates = models.stream()
                .filter(model -> model.modelCode().equals(targetModel))
                .findFirst()
                .map(StandardModelFields::fields)
                .orElse(List.of());
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<FieldSuggestion> best = new ArrayList<>();
        for (String sourceField : sourceFields) {
            SuggestionSource.Context context = new SuggestionSource.Context(
                    sourceField, SuggestionSource.valuesOf(sourceField, sampleRows), targetModel);
            bestFor(context, candidates).ifPresent(best::add);
        }

        // 한 표준 항목에 두 칸이 붙으면 나중 것이 앞의 것을 덮어써 조용히 자료가 어긋난다.
        // 점수가 높은 쪽만 남긴다.
        Set<String> taken = new HashSet<>();
        return best.stream()
                .sorted(Comparator.comparingInt(FieldSuggestion::score).reversed())
                .filter(suggestion -> taken.add(suggestion.targetFieldKey()))
                .toList();
    }

    private java.util.Optional<FieldSuggestion> bestFor(SuggestionSource.Context context,
                                                        List<FieldDefinition> candidates) {
        FieldSuggestion best = null;
        for (FieldDefinition candidate : candidates) {
            int total = 0;
            List<String> reasons = new ArrayList<>();
            for (SuggestionSource source : sources) {
                SuggestionSource.Score score = source.score(context, candidate);
                if (score.scored()) {
                    total += score.points();
                    reasons.add(score.reason());
                }
            }
            if (total >= THRESHOLD && (best == null || total > best.score())) {
                best = new FieldSuggestion(context.sourceField(), candidate.key(), total,
                        reasons);
            }
        }
        return java.util.Optional.ofNullable(best);
    }
}
