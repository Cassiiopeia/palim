package kr.suhsaechan.palim.web.connector;

import java.util.List;

/**
 * 자주 하는 처리를 눌러서 넣는다.
 *
 * <p>파이썬을 모르는 사람이 쓴다는 전제다. <b>빈 상자를 주면 아무도 시작하지 못한다.</b>
 * 돌아가는 글 하나를 미리 넣어 두고, 거기서 한 줄씩 고쳐 나가게 한다.
 *
 * <p>예제는 <b>실제로 받은 자료</b>에서 뽑았다. 「클래식 227g (26.10.17)」·「초콜릿 프로틴바
 * 70g_26.12.12」 처럼 진짜 겪은 모양이라, 붙여 넣으면 바로 쓸모가 있다.
 */
final class PostScriptExamples {

    private PostScriptExamples() {
    }

    /**
     * 처음 여는 사람에게 주는 글.
     *
     * <p>이대로 두어도 돌아간다 — 이름을 그대로 옮겨 담는다. 아무것도 안 하는 것이 아니라
     * <b>안전한 기본</b>이다. 여기서 한 줄씩 고치면 된다.
     */
    static final String STARTER = """
            import sys, json, re

            # 같은 물건인데 표기가 다른 것을 여기 적습니다
            SAME = {
                # "초콜렛": "초콜릿",
            }

            rows = json.load(sys.stdin)["rows"]
            out = []
            for r in rows:
                name = r["raw_item_name"] or ""

                for word, to in SAME.items():
                    name = name.replace(word, to)

                # 괄호와 그 안을 뺍니다   클래식 227g (26.10.17) → 클래식 227g
                name = re.sub(r'\\([^)]*\\)', '', name)

                # 밑줄 뒤 날짜를 뺍니다   초콜릿 프로틴바 70g_26.12.12 → 초콜릿 프로틴바 70g
                name = re.sub(r'_\\d{2}[.\\-/]\\d{2}[.\\-/]\\d{2}$', '', name)

                # 띄어쓰기와 대소문자를 없앱니다 — 견주기 직전에 항상
                name = re.sub(r'\\s+', '', name).lower()

                out.append({"_row": r["_row"], "normalized_name": name})

            print(json.dumps({"rows": out}, ensure_ascii=False))
            """;

    /**
     * @param label   서랍에 보이는 이름
     * @param snippet 커서 자리에 붙는 글
     * @param note    이 처리가 무엇을 하는지 한 줄
     * @param risky   잘못 쓰면 서로 다른 물건이 하나로 뭉개지는가
     */
    record Example(String label, String snippet, String note, boolean risky) {
    }

    static List<Example> all() {
        return List.of(
                new Example("앞뒤 공백",
                        "    name = name.strip()",
                        "「 클래식 」 → 「클래식」", false),

                new Example("괄호 빼기",
                        "    name = re.sub(r'\\([^)]*\\)', '', name)",
                        "클래식 227g (26.10.17) → 클래식 227g", false),

                new Example("날짜 빼기",
                        "    name = re.sub(r'_\\d{2}[.\\-/]\\d{2}[.\\-/]\\d{2}$', '', name)",
                        "초콜릿 프로틴바 70g_26.12.12 → 초콜릿 프로틴바 70g", false),

                new Example("표기 통일",
                        "    name = name.replace(\"초콜렛\", \"초콜릿\")",
                        "두 시스템이 다르게 적는 말을 한쪽에 맞춥니다", false),

                new Example("띄어쓰기·대소문자",
                        "    name = re.sub(r'\\s+', '', name).lower()",
                        "견주기 직전에 항상 합니다", false),

                new Example("규격 떼기",
                        "    name = re.sub(r'\\d+g', '', name)",
                        "⚠ 클래식 16g·227g·425g 가 전부 「클래식」 하나가 됩니다", true),

                new Example("칸 합치기",
                        "    name = f\"{name}/{r.get('product_key') or ''}\"",
                        "이름이 같아도 바코드로 가릅니다", false),

                new Example("빈 값 거르기",
                        "    if not name:\n"
                                + "        continue  # 이 줄은 돌려주지 않습니다 (원본 그대로 담깁니다)",
                        "다듬을 것이 없는 줄은 건드리지 않습니다", false),

                new Example("사람에게 남기는 말",
                        "    print(f\"{r['_row']}번째: {name}\", file=sys.stderr)",
                        "시험 결과에 그대로 보입니다. 디버깅에 씁니다", false));
    }
}
