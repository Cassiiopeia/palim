package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import kr.suhsaechan.palim.connector.source.http.MenuPathProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「어느 메뉴로 들어가 엑셀을 받나」 를 <b>상대 화면에서 읽어</b> 오는가.
 *
 * <p>파일로 대신 채우는 길은 자동 수집이 깨진 날 쓴다. 그때 메뉴를 찾아다니게 하면 우회로가
 * 우회로가 아니다. 그렇다고 <b>코드에 적어 두면 상대가 메뉴를 바꾼 날 거짓말이 된다</b> —
 * 사람은 그 거짓말을 믿고 없는 메뉴를 찾는다. 안 적어 둔 것보다 나쁘다.
 *
 * <p>그래서 물어본다. 그리고 <b>못 찾으면 지어내지 않는다.</b>
 */
class MenuPathProbeTest {

    private final MenuPathProbe probe = new MenuPathProbe();

    /** 수집이 무엇을 보는지는 조회 본문에 이미 적혀 있다. 그것이 이 기능의 출발점이다. */
    @Test
    @DisplayName("조회 본문에서 화면 코드를 읽는다")
    void 화면코드를_읽는다() {
        assertThat(probe.screenCodeOf(Map.of("fetchBody",
                "template=I100&action=search&page_code=I100&rows=500")))
                .isEqualTo("I100");
        assertThat(probe.screenCodeOf(Map.of("fetchBody", "action=search&rows=500")))
                .as("모르면 무엇을 찾아야 할지도 모른다 — 빈 값이어야 한다")
                .isEmpty();
    }

    /** 그 화면 코드를 가리키는 메뉴 항목의 «글자» 를 읽는다. */
    @Test
    @DisplayName("화면 코드를 가리키는 메뉴 이름을 읽는다")
    void 메뉴_이름을_읽는다() {
        String html = """
                <ul class="lnb-menu">
                  <li><span class="menu-title">재고관리</span>
                    <ul>
                      <li><a href="/function.html?template=I100">재고 현황</a></li>
                      <li><a href="/function.html?template=I200">입출고 내역</a></li>
                    </ul>
                  </li>
                </ul>
                """;

        assertThat(probe.menuLabels(html, "I100"))
                .as("사람이 실제로 눌러야 하는 글자여야 한다")
                .contains("재고 현황");
    }

    /**
     * <b>못 찾으면 지어내지 않는다.</b>
     *
     * <p>그럴듯한 경로를 지어내는 것이 이 기능에서 제일 나쁜 결과다 — 사람이 그것을 믿고
     * 없는 메뉴를 찾아 헤맨다. 빈 값이면 화면이 「직접 적어 주세요」 라고 말할 수 있다.
     */
    @Test
    @DisplayName("못 찾으면 빈 목록을 준다 — 지어내지 않는다")
    void 못_찾으면_비운다() {
        String html = "<ul><li><a href=\"/function.html?template=Z999\">다른 화면</a></li></ul>";

        assertThat(probe.menuLabels(html, "I100")).isEmpty();
        assertThat(probe.menuLabels("", "I100")).isEmpty();
        assertThat(probe.menuLabels(html, "")).isEmpty();
    }

    /** 태그가 섞여 있어도 사람이 읽는 글자만 남긴다. */
    @Test
    @DisplayName("아이콘 태그가 섞여도 메뉴 글자만 읽는다")
    void 태그를_걷어낸다() {
        String html = """
                <a href="/function.html?template=I100"><i class="icon"></i>
                   <span>재고&nbsp;현황</span></a>
                """;

        assertThat(probe.menuLabels(html, "I100"))
                .anySatisfy(label -> assertThat(label).isEqualTo("재고 현황"));
    }
}
