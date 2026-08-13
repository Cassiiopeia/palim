package kr.suhsaechan.palim.web.mock;

import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 매핑 화면 <b>시안</b>.
 *
 * <p>확정 전 화면을 실제 레이아웃 안에서 보기 위한 임시 경로다. 아무것도 저장하지 않고 아무
 * 원천도 부르지 않는다 — 값은 전부 이 파일에 적힌 예시다.
 *
 * <p>따로 만든 이유는 <b>시안을 말로 합의하면 어긋나기 때문</b>이다. 화면 밖에서 그린 그림은
 * 실제 레이아웃·글꼴·간격 안에 들어오면 다르게 보이고, 그 차이가 "이게 아닌데"의 원인이 된다.
 *
 * <p>여기 쓰인 뷰 모델({@link MockGroup}·{@link MockRow})은 확정 후 실제 화면이 그대로 받아
 * 쓸 모양으로 만들었다. 시안이 통과하면 데이터 출처만 진짜로 바꾸면 된다.
 */
@Controller
public class MappingMockController {

    /**
     * 매핑 한 줄.
     *
     * @param label     우리 항목 이름 (사람이 읽는 말)
     * @param required  없으면 저장이 안 되는 항목인가
     * @param mode      SELECT(저쪽 칸 고름) · AUTO(시스템이 채움) · CONSTANT(직접 적음) · NONE
     * @param picked    고른 저쪽 칸 이름. AUTO·CONSTANT 면 비어 있다
     * @param constant  직접 적은 값. CONSTANT 일 때만
     * @param preview   그 칸에 실제로 들어있는 값 몇 개. <b>이것이 이 화면의 핵심</b>
     * @param warning   저장 전에 알려야 할 것. 없으면 {@code null}
     */
    public record MockRow(String label, boolean required, String mode, String picked,
                          String constant, String preview, String warning) {

        static MockRow pick(String label, boolean required, String picked, String preview) {
            return new MockRow(label, required, "SELECT", picked, null, preview, null);
        }

        static MockRow pick(String label, boolean required, String picked, String preview,
                            String warning) {
            return new MockRow(label, required, "SELECT", picked, null, preview, warning);
        }

        static MockRow auto(String label, String preview) {
            return new MockRow(label, true, "AUTO", null, null, preview, null);
        }

        static MockRow constant(String label, String value, String preview) {
            return new MockRow(label, false, "CONSTANT", null, value, preview, null);
        }

        static MockRow none(String label) {
            return new MockRow(label, false, "NONE", null, null, null, null);
        }
    }

    /** 성격이 같은 항목 묶음. 29개를 한 줄로 늘어놓으면 읽히지 않는다. */
    public record MockGroup(String title, String hint, List<MockRow> rows) {
    }

    /** 우리 항목에 자리가 없는 저쪽 칸. 버리지 않되 <b>보이게</b> 둔다. */
    public record MockLeftover(String field, String sample, String decision) {
    }

    @GetMapping("/mock/mapping")
    public String mapping(Model model) {
        model.addAttribute("title", "칸 연결하기 (시안)");
        model.addAttribute("sourceFields",
                List.of("WH_CD", "WH_DES", "PROD_CD", "PROD_DES", "PROD_SIZE_DES", "BAL_QTY"));
        model.addAttribute("groups", List.of(
                new MockGroup("꼭 필요한 항목", "비어 있으면 저장되지 않습니다", List.of(
                        MockRow.pick("품목", true, "PROD_CD", "A16P_26.11.07 · B227P_26.10.17"),
                        MockRow.pick("수량", true, "BAL_QTY", "112 · 9,451 · 4",
                                "음수가 1건 있습니다"),
                        MockRow.auto("출처", "이 연동 이름이 들어갑니다"),
                        MockRow.auto("기준 시각", "받아온 시각이 들어갑니다"))),

                new MockGroup("창고·위치", null, List.of(
                        MockRow.pick("창고 코드", false, "WH_CD", "200 · 300"),
                        MockRow.pick("창고 이름", false, "WH_DES", "본사 창고 · 외부 물류"),
                        MockRow.none("로케이션"),
                        MockRow.none("구역"))),

                new MockGroup("수량 상세", null, List.of(
                        MockRow.constant("단위", "EA", "모든 줄에 EA 가 들어갑니다"),
                        MockRow.none("가용 수량"),
                        MockRow.none("할당 수량"),
                        MockRow.none("불량 수량"))),

                new MockGroup("이름", null, List.of(
                        MockRow.pick("원본 품명", false, "PROD_DES", "제품A 16g (26.11.07)"),
                        MockRow.none("정규화 품명"))),

                new MockGroup("로트·기한", "이 원천은 로트를 주지 않습니다", List.of(
                        MockRow.none("로트"),
                        MockRow.none("유통기한"),
                        MockRow.none("제조일")))));

        model.addAttribute("leftovers", List.of(
                new MockLeftover("PROD_SIZE_DES", "제품A 16g (26.11.07)", "그대로 보관"),
                new MockLeftover("REMARK", "(값 없음)", "받지 않기")));
        return "mock/mapping";
    }

    /** 시험 실행 결과 한 줄. 변환을 마친 뒤의 모습이다. */
    public record MockResultRow(String item, String warehouse, String quantity, String unit,
                                String baseAt) {
    }

    /** 담지 못한 줄. <b>왜 못 담았는지</b>가 함께 있어야 고칠 수 있다. */
    public record MockFailedRow(int lineNumber, String reason, String raw) {
    }

    @GetMapping("/mock/run")
    public String run(Model model) {
        model.addAttribute("title", "시험 실행 결과 (시안)");
        model.addAttribute("rows", List.of(
                new MockResultRow("A16P_26.11.07", "200 · 본사 창고", "112", "EA",
                        "2026-08-13 18:33"),
                new MockResultRow("A16P_26.11.07", "300 · 외부 물류", "9,451", "EA",
                        "2026-08-13 18:33"),
                new MockResultRow("B227P_26.10.17", "200 · 본사 창고", "4", "EA",
                        "2026-08-13 18:33"),
                new MockResultRow("B227P_27.04.07", "200 · 본사 창고", "1", "EA",
                        "2026-08-13 18:33")));
        model.addAttribute("failed", List.of(
                new MockFailedRow(2, "수량이 「-」 라 숫자로 바꿀 수 없습니다",
                        "{\"PROD_CD\":\"A0001\",\"BAL_QTY\":\"-\"}"),
                new MockFailedRow(17, "품목 칸이 비어 있습니다",
                        "{\"PROD_CD\":\"\",\"BAL_QTY\":\"30\"}")));
        return "mock/run";
    }
}
