package kr.suhsaechan.palim.reconcile.filter;

import java.util.Arrays;
import java.util.List;

/**
 * 화면이 값 여럿을 <b>한 칸에</b> 담아 보내는 방법.
 *
 * <p>줄마다 값 칸을 여러 개 두면 화면이 보낸 세 목록(칸·연산자·값)의 길이가 어긋나 어느 값이
 * 어느 줄 것인지 알 수 없게 된다. 한 칸에 담고 구분자로 나누면 <b>줄 수와 칸 수가 언제나 같다.</b>
 *
 * <p>구분자를 세로줄로 고른 이유는 창고 코드·품질상태 같은 값에 들어갈 일이 없어서다.
 */
public final class FilterValues {

    /** 값을 잇는 글자. */
    public static final String DELIMITER = "|";

    private FilterValues() {
    }

    /** 한 칸에 담겨 온 값을 나눈다. 빈 조각은 버린다 — 「,,」 로 빈 값이 생기면 조건이 이상해진다. */
    public static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("\\|", -1))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
