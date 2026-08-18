package kr.suhsaechan.palim.connector.source.http;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 로그인한 화면에서 <b>「어느 메뉴로 들어가는 화면인가」</b> 를 읽는다.
 *
 * <h2>왜 코드에 적어 두지 않나</h2>
 *
 * <p>파일로 대신 채우는 길은 <b>자동 수집이 깨진 날</b> 쓴다. 그때 「어느 메뉴에서 엑셀을
 * 받나」 를 찾아다니게 하면 우회로가 우회로가 아니다. 그래서 안내를 화면에 둔다.
 *
 * <p>그런데 그 안내를 <b>코드에 적어 두면 상대가 메뉴를 바꾼 날 거짓말이 된다.</b> 그리고
 * 사람은 그 거짓말을 믿고 없는 메뉴를 찾는다 — 안 적어 둔 것보다 나쁘다.
 *
 * <p>그래서 <b>물어본다.</b> 수집이 실제로 보는 화면 코드({@code template=I100} 같은 값)를
 * 이미 알고 있으므로, 로그인한 화면에서 그 코드를 가리키는 메뉴 항목을 찾아 그 이름과 상위
 * 메뉴를 읽는다. 상대가 메뉴를 바꾸면 <b>다시 눌러 다시 읽으면 된다.</b>
 *
 * <p><b>못 찾으면 지어내지 않는다.</b> 빈 값을 돌려주고 화면이 「못 찾았다」 고 말한다 —
 * 그럴듯한 경로를 지어내는 것이 이 기능에서 제일 나쁜 결과다.
 */
@Slf4j
@Component
public class MenuPathProbe {

    /** 메뉴 글자로 보기 어려운 것. 이런 것만 남으면 못 찾은 것이다. */
    private static final Set<String> NOISE = Set.of("", "-", "·", "|", "&nbsp;");

    /** 한 화면에서 거둘 메뉴 이름의 최대 수. 더 많으면 메뉴가 아니라 목록을 긁은 것이다. */
    private static final int MAX_LABELS = 8;

    /**
     * 이 화면 코드를 가리키는 메뉴 항목의 이름들.
     *
     * @param html       로그인한 채로 받아 온 화면
     * @param screenCode 수집이 실제로 보는 화면 코드. 없으면 빈 목록
     * @return 바깥 메뉴부터 안쪽 순서. 못 찾으면 빈 목록
     */
    public List<String> menuLabels(String html, String screenCode) {
        if (html == null || html.isBlank() || screenCode == null || screenCode.isBlank()) {
            return List.of();
        }

        Set<String> found = new LinkedHashSet<>();
        // 화면 코드가 박힌 태그를 찾아 그 태그가 감싼 글자를 읽는다. href·onclick·data-* 어디에
        // 있든 걸리도록 태그 하나를 통째로 본다 — 상대마다 메뉴를 만드는 방식이 다르다.
        Matcher anchor = Pattern.compile(
                        "<a[^>]*" + Pattern.quote(screenCode) + "[^>]*>(.*?)</a>",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(html);
        while (anchor.find() && found.size() < MAX_LABELS) {
            String label = text(anchor.group(1));
            if (!NOISE.contains(label)) {
                found.add(label);
            }
        }

        if (found.isEmpty()) {
            log.debug("화면 코드를 가리키는 메뉴를 찾지 못했습니다 — 화면코드={}", screenCode);
            return List.of();
        }

        List<String> labels = new ArrayList<>(found);
        // 상위 메뉴는 그 항목 «앞» 에 나온 묶음 제목이다. 못 찾으면 붙이지 않는다 — 지어낸
        // 상위 메뉴는 사람을 엉뚱한 곳으로 보낸다.
        String parent = parentOf(html, screenCode);
        if (!parent.isBlank() && !labels.contains(parent)) {
            labels.add(0, parent);
        }
        log.info("메뉴 경로를 읽었습니다 — 화면코드={} 경로={}", screenCode, labels);
        return labels;
    }

    /**
     * 그 항목을 감싼 <b>바깥 메뉴</b> 이름.
     *
     * <p>항목이 나온 자리 앞쪽에서 가장 가까운 묶음 제목을 찾는다. 메뉴를 만드는 방식이
     * 상대마다 달라 확실하지 않으므로, <b>확신이 없으면 빈 값</b>을 준다.
     */
    private String parentOf(String html, String screenCode) {
        int at = html.indexOf(screenCode);
        if (at < 0) {
            return "";
        }
        String before = html.substring(0, at);
        Matcher group = Pattern.compile(
                        "<(?:h[1-6]|span|div|a|li)[^>]*(?:menu|nav|gnb|lnb|depth)[^>]*>([^<]{2,20})<",
                        Pattern.CASE_INSENSITIVE)
                .matcher(before);
        String parent = "";
        while (group.find()) {
            String label = text(group.group(1));
            if (!NOISE.contains(label)) {
                parent = label;
            }
        }
        return parent;
    }

    /** 태그를 걷어내고 사람이 읽는 글자만 남긴다. */
    private String text(String raw) {
        return raw.replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 수집이 보는 화면 코드.
     *
     * <p>조회 요청 본문에 {@code template=I100} 처럼 들어 있다. 이 값을 이미 알고 있다는 것이
     * 이 기능이 성립하는 근거다 — 모르면 무엇을 찾아야 할지도 모른다.
     */
    public String screenCodeOf(Map<String, String> config) {
        String body = config.getOrDefault("fetchBody", "");
        Matcher template = Pattern.compile("(?:template|page_code)=([A-Za-z0-9_]+)").matcher(body);
        return template.find() ? template.group(1) : "";
    }
}
