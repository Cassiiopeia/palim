package kr.suhsaechan.palim.reconcile.match;

import java.util.HashSet;
import java.util.Set;

/**
 * 두 이름이 얼마나 닮았나 — <b>고르는 순서를 정하는 데만</b> 쓴다.
 *
 * <p>왜 필요한가. 자동 후보는 다듬은 이름이 <b>정확히</b> 같아야 잡힌다. 그래서 「초콜릿
 * 프로틴바」 와 「초콜렛 프로틴바」 는 사람 눈에 명백히 같은데도 영영 안 만난다. 그런 품목이
 * 수십 개면 목록을 처음부터 끝까지 눈으로 훑어야 하고, 그게 이 화면에서 제일 지치는 일이다.
 *
 * <p><b>점수로 자동 확정하지 않는다.</b> 오직 반대쪽 목록을 «닮은 것부터» 늘어놓을 뿐이다.
 * 점수가 판단을 대신하면 규칙이 틀렸을 때 엉뚱한 품목을 합쳐 놓고 「재고가 맞는다」 고
 * 보고하게 되는데, 그건 못 찾는 것보다 나쁘다 — 틀렸다는 사실조차 드러나지 않는다.
 *
 * <p>글자 두 개씩 잘라 겹치는 비율을 본다(Dice). 한국어에서 잘 듣고, 편집 거리와 달리 길이가
 * 크게 다른 이름에 지나치게 관대하지 않다.
 */
final class NameSimilarity {

    private NameSimilarity() {
    }

    /**
     * @return 0.0(전혀 안 닮음) ~ 1.0(같음)
     */
    static double score(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return 0.0;
        }
        if (left.equals(right)) {
            return 1.0;
        }
        Set<String> a = bigrams(left);
        Set<String> b = bigrams(right);
        if (a.isEmpty() || b.isEmpty()) {
            // 한 글자짜리 이름. 같으면 위에서 걸렸으므로 여기 오면 다른 것이다.
            return 0.0;
        }
        int shared = 0;
        for (String gram : a) {
            if (b.contains(gram)) {
                shared++;
            }
        }
        return 2.0 * shared / (a.size() + b.size());
    }

    private static Set<String> bigrams(String value) {
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 1 < value.length(); i++) {
            grams.add(value.substring(i, i + 2));
        }
        return grams;
    }
}
