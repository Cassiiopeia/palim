package kr.suhsaechan.palim.reconcile.match;

import java.util.List;

/**
 * 여러 품목을 한 물건으로 묶을 때 <b>무엇이라 부를 것인가.</b>
 *
 * <p>예전에는 <b>첫 품목의 이름을 그대로</b> 물건 이름으로 썼다. 그래서 로트 네 개를 묶은
 * 물건이 「클래식 850g (27.03.16)」 이 되었다 — <b>특정 로트 날짜가 전체를 대표</b>하게 된 것이다.
 * 목록에서 그 이름을 보면 그 로트 하나에 대한 이야기로 읽히고, 실제로는 네 로트의 합이다.
 *
 * <p>그래서 여럿이 묶일 때는 <b>이름들의 공통 부분</b>만 쓴다. 지어내는 것이 아니라 담긴
 * 자료에 실제로 있는 글자에서 잘라 내는 것이고, <b>어차피 사람이 고칠 수 있다</b>(07-DECISIONS
 * 038). 하나뿐이면 자를 것이 없으므로 그 이름을 그대로 쓴다.
 */
public final class CommonName {

    /** 이보다 짧아지면 무슨 물건인지 알 수 없다 — 그럴 바엔 원래 이름이 낫다. */
    private static final int MIN_LENGTH = 2;

    private CommonName() {
    }

    /**
     * 좌·우 품명들로 물건 이름을 짓는다.
     *
     * <p><b>품목이 많은 쪽</b>을 기준으로 삼는다. 로트가 갈리는 쪽이 대개 그쪽이고, 두 시스템은
     * 애초에 이름을 다르게 적으므로 양쪽을 섞어 공통 부분을 뽑으면 「초콜」 같은 토막이 남는다.
     *
     * @return 지을 이름. 재료가 없으면 빈 문자열
     */
    public static String of(List<String> leftNames, List<String> rightNames) {
        List<String> base = leftNames.size() >= rightNames.size() ? leftNames : rightNames;
        List<String> usable = base.stream()
                .filter(name -> name != null && !name.isBlank())
                .toList();
        if (usable.isEmpty()) {
            return "";
        }
        if (usable.size() == 1) {
            // 자를 것이 없다. 하나뿐인 품목의 이름이 곧 그 물건의 이름이다.
            return usable.getFirst().trim();
        }

        String prefix = usable.getFirst();
        for (String name : usable) {
            prefix = commonPrefix(prefix, name);
            if (prefix.isEmpty()) {
                break;
            }
        }
        String trimmed = tidy(prefix);
        return trimmed.length() >= MIN_LENGTH ? trimmed : usable.getFirst().trim();
    }

    private static String commonPrefix(String a, String b) {
        int at = 0;
        int limit = Math.min(a.length(), b.length());
        while (at < limit && a.charAt(at) == b.charAt(at)) {
            at++;
        }
        return a.substring(0, at);
    }

    /**
     * 잘린 자리를 다듬는다.
     *
     * <p>공통 부분은 대개 <b>괄호 안에서 끊긴다</b> — 「클래식 850g (27.」 처럼. 열린 채 끝난
     * 괄호는 통째로 버리고, 남은 꼬리의 구분 기호도 턴다. 그러지 않으면 목록에 반쪽짜리
     * 괄호가 줄줄이 남는다.
     */
    private static String tidy(String value) {
        String result = value;
        int cut = lastUnclosed(result);
        if (cut >= 0) {
            result = result.substring(0, cut);
        }
        int end = result.length();
        while (end > 0 && isTrailingNoise(result.charAt(end - 1))) {
            end--;
        }
        return result.substring(0, end).trim();
    }

    /** 열린 채 닫히지 않은 괄호의 자리. 없으면 -1. */
    private static int lastUnclosed(String value) {
        int depth = 0;
        int opened = -1;
        for (int at = 0; at < value.length(); at++) {
            char ch = value.charAt(at);
            if (ch == '(' || ch == '[' || ch == '{') {
                if (depth == 0) {
                    opened = at;
                }
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth = Math.max(0, depth - 1);
                if (depth == 0) {
                    opened = -1;
                }
            }
        }
        return depth > 0 ? opened : -1;
    }

    private static boolean isTrailingNoise(char ch) {
        return Character.isWhitespace(ch)
                || ch == '-' || ch == '_' || ch == '/' || ch == ',' || ch == '.'
                || ch == '·' || ch == ':' || ch == ';' || ch == '(' || ch == '[' || ch == '{';
    }
}
