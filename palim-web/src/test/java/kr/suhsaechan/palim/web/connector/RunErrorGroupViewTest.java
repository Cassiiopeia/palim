package kr.suhsaechan.palim.web.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실패한 줄을 <b>사람이 읽을 수 있게</b> 묶는가.
 *
 * <p>사장님이 실행 결과를 보고 「왤케 이해하기가 어렵냐」 고 했다. 화면은 이랬다.
 *
 * <pre>
 * 1  REQUIRED_FIELD_MISSING(K007) args=[item_ref]   {"key":"00094","barcode":"CY99…",… 23개}
 * 2  REQUIRED_FIELD_MISSING(K007) args=[item_ref]   {"key":"01002",…}
 * …  (같은 줄이 24번)
 * </pre>
 *
 * <p>여기에는 <b>고칠 수 있는 정보가 없다.</b> 코드 이름은 사람 말이 아니고, 같은 원인이 24번
 * 반복되며, 원문 23칸 중 무엇을 봐야 하는지 알려주지 않는다. 결국 서버 로그를 뒤져야 원인을
 * 찾을 수 있었다 — 확인하라고 만든 화면이 확인을 못 하게 한 셈이다.
 */
class RunErrorGroupViewTest {

    private static final String SOURCE_ROW = """
            {"key": "00094", "barcode": "CY9900094", "options": "", \
            "product_name": "제품A 198g (26.11.26)", "stock_normal": "425"}""";

    private static final Map<String, String> LABELS = Map.of(
            "item_ref", "품목", "quantity", "수량");

    @Test
    @DisplayName("같은 원인 24줄을 한 덩어리로 묶는다")
    void 같은_원인을_묶는다() {
        List<RunErrorRow> errors = new java.util.ArrayList<>();
        for (int row = 1; row <= 24; row++) {
            errors.add(new RunErrorRow(row, "REQUIRED_FIELD_MISSING",
                    "REQUIRED_FIELD_MISSING(K007) args=[item_ref]", SOURCE_ROW));
        }

        List<RunErrorGroupView> groups =
                RunErrorGroupView.of(errors, LABELS, Map.of("item_ref", "key"));

        assertThat(groups)
                .as("같은 말이 24번 나오면 사람은 읽기를 포기한다")
                .hasSize(1);
        assertThat(groups.getFirst().count()).isEqualTo(24);
        assertThat(groups.getFirst().rowRange()).isEqualTo("1–24번 줄");
    }

    @Test
    @DisplayName("코드가 아니라 사람 말로 원인을 적는다")
    void 사람_말로_적는다() {
        List<RunErrorGroupView> groups = RunErrorGroupView.of(
                List.of(new RunErrorRow(1, "REQUIRED_FIELD_MISSING",
                        "REQUIRED_FIELD_MISSING(K007) args=[item_ref]", SOURCE_ROW)),
                LABELS, Map.of("item_ref", "key"));

        assertThat(groups.getFirst().title())
                .as("「K007」 이 무엇인지 찾으러 화면을 떠나게 하면 안 된다")
                .isEqualTo("「품목」 이(가) 비어 있습니다");
    }

    /**
     * 할 일이 상황마다 다르다.
     *
     * <p>칸을 <b>안 고른 것</b>과 <b>골랐는데 그 칸이 빈 것</b>은 원인이 같아 보여도 해야 할
     * 일이 정반대다. 하나로 뭉뚱그리면 사람은 이미 한 일을 또 하러 간다.
     */
    @Test
    @DisplayName("칸을 안 골랐을 때와 골랐는데 빈 때를 구분해 말한다")
    void 할_일을_구분한다() {
        RunErrorRow error = new RunErrorRow(1, "REQUIRED_FIELD_MISSING",
                "REQUIRED_FIELD_MISSING(K007) args=[item_ref]", SOURCE_ROW);

        String notPicked = RunErrorGroupView.of(List.of(error), LABELS, Map.of())
                .getFirst().hint();
        String pickedButEmpty = RunErrorGroupView.of(List.of(error), LABELS,
                Map.of("item_ref", "options")).getFirst().hint();

        assertThat(notPicked).contains("아직 고르지 않았습니다");
        assertThat(pickedButEmpty)
                .contains("options")
                .contains("다른 칸을 골라");
    }

    /**
     * 원문 스무 칸을 그대로 뿌리지 않는다.
     *
     * <p>사람이 가장 먼저 보고 싶은 것은 <b>내가 고른 칸에 무엇이 들어 있나</b> 다. 그것을
     * 맨 앞에 놓고 나머지는 몇 개만 붙인다.
     */
    @Test
    @DisplayName("고른 칸의 값을 맨 앞에 보여준다")
    void 고른_칸을_먼저_보여준다() {
        List<RunErrorGroupView> groups = RunErrorGroupView.of(
                List.of(new RunErrorRow(1, "REQUIRED_FIELD_MISSING",
                        "REQUIRED_FIELD_MISSING(K007) args=[item_ref]", SOURCE_ROW)),
                LABELS, Map.of("item_ref", "options"));

        assertThat(groups.getFirst().evidence().getFirst())
                .as("고른 칸이 비어 있다는 사실이 곧 답이다")
                .isEqualTo("options = (비어 있음)");
        assertThat(groups.getFirst().evidence())
                .as("칸을 전부 뿌리면 원문을 그대로 보여주는 것과 같다")
                .hasSizeLessThanOrEqualTo(6);
    }
}
