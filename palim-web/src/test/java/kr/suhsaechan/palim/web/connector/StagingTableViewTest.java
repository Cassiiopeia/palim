package kr.suhsaechan.palim.web.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 담길 모습을 <b>사실대로</b> 보여주는가.
 *
 * <p>이 화면의 존재 이유는 「진짜로 넣기 전에 눈으로 보는 것」이다. 그런데 화면이 값을 손대서
 * 보여주면 <b>확인 단계 자체가 거짓</b>이 된다. 담긴 것은 멀쩡한데 화면만 다르게 말하므로,
 * 사람은 멀쩡한 자료를 잘못됐다고 보거나 그 반대로 본다.
 *
 * <p>실제로 품목코드 {@code 00094} 가 {@code 94} 로, {@code 01002} 가 {@code 1,002} 로
 * 보였다. 숫자처럼 생긴 글자를 숫자로 바꾸면서 앞자리 0 이 사라지고 천 단위 쉼표가 붙었다.
 * 기준 시각은 {@code 1,786,719,600} 이라는 정체불명의 숫자로 나왔다.
 */
class StagingTableViewTest {

    private static final Set<String> MOMENTS = Set.of("base_at", "collected_at");

    private static StagingRow row(String payload) {
        return new StagingRow(1, "00094", payload);
    }

    /**
     * 앞자리 0 이 살아 있는가.
     *
     * <p>품목코드는 <b>글자</b>다. 숫자로 바꾸는 순간 {@code 00094} 와 {@code 94} 가 같아지고,
     * 두 시스템의 코드를 견주는 이 제품의 목적 자체가 무너진다.
     */
    @Test
    @DisplayName("품목코드의 앞자리 0 을 지우지 않는다")
    void 코드를_숫자로_바꾸지_않는다() {
        var view = StagingTableView.of(List.of(row("""
                {"item_ref":"00094","product_key":"CY9900094"}""")), MOMENTS);

        assertThat(view.getRows().getFirst().values())
                .as("«00094» 가 «94» 로 보이면 담긴 것이 잘못됐다고 오해한다")
                .containsExactly("00094", "CY9900094");
    }

    /** 수량은 반대로 <b>읽기 쉽게</b> 다듬어야 한다. 둘을 같은 규칙으로 다루면 하나는 반드시 깨진다. */
    @Test
    @DisplayName("수량은 자릿수를 읽을 수 있게 다듬는다")
    void 수량은_다듬는다() {
        var view = StagingTableView.of(List.of(row("""
                {"quantity":9451.0000000000}""")), MOMENTS);

        assertThat(view.getRows().getFirst().values())
                .as("9451.0000000000 을 그대로 두면 자릿수를 눈으로 셀 수 없다")
                .containsExactly("9,451");
    }

    /**
     * 시각을 시각으로 보여주는가.
     *
     * <p>안에서는 「1970년부터 몇 초」로 다룬다. 그대로 뿌리면 사람이 읽을 수 없을 뿐 아니라
     * <b>수량과 구분되지도 않는다</b> — 둘 다 그냥 큰 숫자로 보인다.
     */
    @Test
    @DisplayName("기준 시각을 숫자가 아니라 시각으로 보여준다")
    void 시각을_시각으로_보여준다() {
        var view = StagingTableView.of(List.of(row("""
                {"base_at":1786719600,"collected_at":1786758915.1227744}""")), MOMENTS);

        assertThat(view.getRows().getFirst().values())
                .as("«1,786,719,600» 은 사람이 읽을 수 있는 시각이 아니다")
                .noneMatch(value -> value.contains("1,786"))
                .allMatch(value -> value.matches("\\d{2}-\\d{2} \\d{2}:\\d{2}"));
    }
}
