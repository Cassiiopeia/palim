package kr.suhsaechan.palim.connector.script;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.connector.excel.ConnectorScriptProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 사장님이 쓴 스크립트가 <b>실제로 돌고, 잘못 써도 안전한가</b>.
 *
 * <p>진짜 파이썬을 돌린다. 흉내 낸 것으로 확인하면 「우리 코드끼리는 말이 맞는다」까지만 알
 * 수 있고, 정작 프로세스·인코딩·JSON 왕복에서 깨지는 것을 못 잡는다.
 *
 * <p>여기서 지키는 것은 둘이다. <b>제대로 쓰면 그대로 돈다</b>는 것과, <b>잘못 써도 자료가
 * 망가지지 않는다</b>는 것. 이 코드는 사장님이 화면에서 직접 쓰는 글을 실행하므로, 틀리게
 * 쓰는 일이 예외가 아니라 <b>일상</b>이다.
 */
class PostScriptRunnerTest {

    private final PostScriptRunner runner = new PostScriptRunner(
            new ConnectorScriptProperties("python3", "scripts", 30));

    private static final List<Map<String, Object>> ROWS = List.of(
            Map.of("item_ref", "N198P_26.11.26", "raw_item_name", "노슈거 198g (26.11.26)"),
            Map.of("item_ref", "01013", "raw_item_name", "초콜렛 프로틴바"));

    /** 설계 문서에 적은 계약 그대로. 이것이 안 돌면 예제도 문서도 전부 거짓이 된다. */
    private static final String NORMALIZE = """
            import sys, json, re

            SAME = {"초콜렛": "초콜릿"}
            RULES = [(r'\\([^)]*\\)', ''), (r'_\\d{2}[.\\-/]\\d{2}[.\\-/]\\d{2}$', '')]

            rows = json.load(sys.stdin)["rows"]
            out = []
            for r in rows:
                name = r["raw_item_name"] or ""
                for word, to in SAME.items():
                    name = name.replace(word, to)
                for pat, rep in RULES:
                    name = re.sub(pat, rep, name)
                out.append({"item_ref": r["item_ref"],
                            "normalized_name": re.sub(r'\\s+', '', name).lower()})

            print(json.dumps({"rows": out}, ensure_ascii=False))
            """;

    @Test
    @DisplayName("계약대로 쓴 스크립트는 그대로 돈다")
    void 계약대로_쓰면_돈다() {
        PostScriptResult result = runner.run(NORMALIZE, ROWS, 30_000);

        assertThat(result.isSucceeded())
                .as("사유: %s", result.message())
                .isTrue();
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0))
                .containsEntry("normalized_name", "노슈거198g");
        assertThat(result.rows().get(1))
                .as("표기가 달라 안 묶이던 것이 묶여야 이 기능의 의미가 있다")
                .containsEntry("normalized_name", "초콜릿프로틴바");
    }

    /** 한글이 프로세스를 오가며 깨지지 않는가. Windows 개발 환경에서 실제로 겪는 함정이다. */
    @Test
    @DisplayName("한글이 오가면서 깨지지 않는다")
    void 한글이_깨지지_않는다() {
        PostScriptResult result = runner.run("""
                import sys, json
                rows = json.load(sys.stdin)["rows"]
                print(json.dumps({"rows": [{"item_ref": r["item_ref"],
                                            "normalized_name": r["raw_item_name"]}
                                           for r in rows]}, ensure_ascii=False))
                """, ROWS, 30_000);

        assertThat(result.rows().get(0))
                .containsEntry("normalized_name", "노슈거 198g (26.11.26)");
    }

    /**
     * 문법이 틀렸을 때.
     *
     * <p>예외로 터지면 어딘가에서 「처리 중 오류가 발생했습니다」로 뭉개진다. 사장님은 자기가
     * 쓴 글의 <b>몇 번째 줄이 틀렸는지</b>를 봐야 고칠 수 있다.
     */
    @Test
    @DisplayName("문법이 틀리면 파이썬이 한 말을 그대로 돌려준다")
    void 문법이_틀리면_이유를_돌려준다() {
        PostScriptResult result = runner.run("이건 파이썬이 아니다", ROWS, 30_000);

        assertThat(result.isSucceeded()).isFalse();
        assertThat(result.message())
                .as("무엇이 틀렸는지 안 보이면 고칠 수가 없다")
                .contains("SyntaxError");
    }

    /** JSON 이 아닌 것을 뱉으면 — 사람용 메시지를 stdout 에 섞는 것이 가장 흔한 실수다. */
    @Test
    @DisplayName("JSON 이 아닌 것을 돌려주면 무엇이 잘못됐는지 짚어 준다")
    void JSON이_아니면_짚어준다() {
        PostScriptResult result = runner.run("""
                import sys, json
                json.load(sys.stdin)
                print("다 됐습니다")
                """, ROWS, 30_000);

        assertThat(result.isSucceeded()).isFalse();
        assertThat(result.message()).contains("stderr");
    }

    /**
     * 끝나지 않는 스크립트.
     *
     * <p>무한 반복은 실수로 아주 쉽게 쓰인다. 끊지 않으면 그 실행이 영영 안 끝나고, 같은
     * 연동이 잠긴다 — 오늘 그것 때문에 이카운트가 하루 종일 막혀 있었다.
     */
    @Test
    @DisplayName("끝나지 않는 스크립트는 끊는다")
    void 끝나지_않으면_끊는다() {
        PostScriptResult result = runner.run("""
                import sys, json
                json.load(sys.stdin)
                while True:
                    pass
                """, ROWS, 1_500);

        assertThat(result.status())
                .as("끊지 않으면 그 연동이 영영 잠긴다")
                .isEqualTo(PostScriptResult.Status.TIMEOUT);
    }

    /** 사람에게 할 말은 stderr 로. 그것이 화면에 그대로 보여야 디버깅이 된다. */
    @Test
    @DisplayName("스크립트가 남긴 말을 그대로 전한다")
    void 남긴_말을_전한다() {
        PostScriptResult result = runner.run("""
                import sys, json
                rows = json.load(sys.stdin)["rows"]
                print(f"{len(rows)}건 받았습니다", file=sys.stderr)
                print(json.dumps({"rows": []}))
                """, ROWS, 30_000);

        assertThat(result.isSucceeded()).isTrue();
        assertThat(result.message()).contains("2건 받았습니다");
    }
}
