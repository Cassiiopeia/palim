package kr.suhsaechan.palim.connector.suggest;

import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.connector.model.FieldDefinition;

/**
 * 추천 근거 하나.
 *
 * <p>근거를 나눠 둔 이유는 <b>하나만 쓰면 반드시 틀리기 때문</b>이다. 사전은 우리가 아는 이름만
 * 잡고, 이름 규칙은 오탐하며, 값 모양은 수량과 단가를 구분하지 못한다. 각자 독립적으로 점수를
 * 내고 합산해야 서로의 빈틈을 메운다.
 *
 * <p>구현체를 빈으로 등록하면 {@link FieldSuggester} 를 고치지 않고 근거가 늘어난다.
 * 원천 어댑터가 늘어나는 방식과 같다.
 */
public interface SuggestionSource {

    /**
     * 이 원천 칸이 그 표준 항목일 가능성에 점수를 매긴다.
     *
     * @param sourceField 원천 칸 이름
     * @param samples     그 칸에 실제로 들어 있는 값 몇 개
     * @param candidate   따져 볼 표준 항목
     * @return 점수와 근거. 해당 없으면 {@link Score#none()}
     */
    Score score(String sourceField, List<String> samples, FieldDefinition candidate);

    /**
     * 점수와 사람이 읽는 근거.
     *
     * @param points 0 이면 근거 없음
     * @param reason 화면에 그대로 보여줄 문장. 점수가 0 이면 {@code null}
     */
    record Score(int points, String reason) {

        private static final Score NONE = new Score(0, null);

        public static Score none() {
            return NONE;
        }

        public static Score of(int points, String reason) {
            return new Score(points, reason);
        }

        public boolean scored() {
            return points > 0;
        }
    }

    /** 이름 비교용 정규화. 대소문자·밑줄·공백·하이픈 차이는 같은 이름으로 본다. */
    static String normalize(String raw) {
        return raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s_\\-]", "");
    }

    /** 샘플에서 특정 칸의 값만 뽑는다. 비어 있는 값은 판단에 도움이 안 되므로 뺀다. */
    static List<String> valuesOf(String sourceField, List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> row.get(sourceField))
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .filter(value -> !value.isBlank())
                .toList();
    }
}
