package kr.suhsaechan.palim.web.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 담길 모습을 <b>손대지 않고</b> 보여주는가.
 *
 * <p>이 화면의 존재 이유는 「진짜로 넣기 전에 눈으로 보는 것」이다. 화면이 값을 다듬어서
 * 보여주면 <b>확인 단계 자체가 거짓</b>이 된다 — 담긴 것과 보이는 것이 다르므로, 멀쩡한
 * 자료를 잘못됐다고 보거나 그 반대로 본다.
 *
 * <p>실제로 품목코드 {@code 00094} 가 {@code 94} 로, {@code 01002} 가 {@code 1,002} 로
 * 보였다. 숫자처럼 «생긴» 글자를 숫자로 다듬으면서 앞자리 0 이 사라지고 쉼표가 붙었다.
 *
 * <p>읽기 좋게 만드는 일은 <b>담을 때</b> 끝낸다. 화면에서 되돌리려 하면 「어느 칸이 무슨
 * 값인지」를 화면이 또 판단해야 하고, 판단이 두 군데로 갈리면 언젠가 어긋난다.
 */
class StagingTableViewTest {

    private static StagingRow row(String payload) {
        return new StagingRow(1, "00094", payload);
    }

    /**
     * 앞자리 0 이 살아 있는가.
     *
     * <p>품목코드를 숫자로 바꾸는 순간 {@code 00094} 와 {@code 94} 가 같아진다. 두 시스템의
     * 코드를 견주는 이 제품의 목적이 거기서 무너진다.
     */
    @Test
    @DisplayName("담긴 값을 그대로 보여준다")
    void 담긴_값을_그대로_보여준다() {
        var view = StagingTableView.of(List.of(row("""
                {"item_ref":"00094","product_key":"CY9900094","quantity":425}""")));

        assertThat(view.getRows().getFirst().values())
                .as("화면이 값을 다듬으면 확인이 거짓이 된다")
                .containsExactly("00094", "CY9900094", "425");
    }

    /**
     * 시각도 담긴 그대로.
     *
     * <p>읽을 수 있는 것은 <b>담을 때</b> 그렇게 담았기 때문이다. 화면이 되돌린 것이 아니다 —
     * 화면이 되돌리면 어느 칸이 시각인지 화면이 알아야 하고, 그 판단이 또 하나 늘어난다.
     */
    @Test
    @DisplayName("시각도 담긴 글자를 그대로 보여준다")
    void 시각도_그대로다() {
        var view = StagingTableView.of(List.of(row("""
                {"base_at":"2026-08-15T00:00:00Z"}""")));

        assertThat(view.getRows().getFirst().values())
                .containsExactly("2026-08-15T00:00:00Z");
    }

    /**
     * 중첩 객체만 예외다.
     *
     * <p>{@code attributes} 를 통째로 펼치면 표가 옆으로 끝없이 늘어나 정작 봐야 할 수량·품목이
     * 화면 밖으로 밀린다. 값을 <b>바꾸는</b> 것이 아니라 <b>접는</b> 것이라 확인을 해치지 않는다.
     */
    @Test
    @DisplayName("중첩된 값은 접어 둔다")
    void 중첩된_값은_접는다() {
        var view = StagingTableView.of(List.of(row("""
                {"item_ref":"00094","attributes":{"supply_name":"공급처"}}""")));

        assertThat(view.getRows().getFirst().values()).containsExactly("00094", "…");
    }
}
