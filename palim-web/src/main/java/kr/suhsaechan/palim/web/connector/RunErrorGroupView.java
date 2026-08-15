package kr.suhsaechan.palim.web.connector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 담지 못한 줄을 <b>원인별로 묶은 것</b>.
 *
 * <p>예전에는 실패한 줄을 하나씩 그대로 늘어놓았다. 24줄이 같은 이유로 떨어지면 <b>같은 말이
 * 24번</b> 나오고, 그 옆에는 칸 23개짜리 JSON 이 통째로 붙었다. 화면을 아무리 봐도 무엇을
 * 고쳐야 하는지 알 수 없었다 — 실제로 그 화면을 보고도 원인을 못 찾아 로그를 뒤져야 했다.
 *
 * <p>원인은 대개 <b>몇 가지뿐</b>이다. 줄 수는 「얼마나 퍼졌는지」를 말할 뿐이고, 사람이 알아야
 * 하는 것은 <b>무엇이 왜 안 됐고 어디를 고치면 되는지</b>다.
 *
 * @param title    사람 말로 쓴 원인 한 줄
 * @param hint     그래서 무엇을 하면 되는지. 이것이 없으면 원인을 알아도 멈춘다
 * @param count    같은 원인으로 떨어진 줄 수
 * @param rowRange 몇 번째 줄들인지 («1–24번 줄» 처럼)
 * @param evidence 그 줄이 실제로 갖고 있던 값. 판단 근거가 되는 것만 골라 담는다
 * @param raw      상대가 보낸 원문. 접어 둔다 — 필요한 사람만 편다
 */
public record RunErrorGroupView(String title, String hint, String errorCode, int count,
                                String rowRange, List<String> evidence, String raw) {

    /** 로그용 문자열에서 어느 칸 때문인지 뽑아낸다. {@code REQUIRED_FIELD_MISSING(K007) args=[item_ref]} */
    private static final Pattern ARGS = Pattern.compile("args=\\[([^\\]]*)]");

    /** 근거로 보여줄 값의 최대 개수. 다 보여주면 JSON 을 뿌리는 것과 같아진다. */
    private static final int EVIDENCE_LIMIT = 6;

    /**
     * 실패한 줄들을 원인별로 묶는다.
     *
     * @param fieldLabels 표준 칸 이름 → 사람이 읽는 이름 ({@code item_ref} → 「품목」)
     * @param sourceOf    표준 칸에 어느 원천 칸을 쓰기로 했는지. 「어디를 고치면 되는지」에 쓴다
     */
    public static List<RunErrorGroupView> of(List<RunErrorRow> errors,
                                             Map<String, String> fieldLabels,
                                             Map<String, String> sourceOf) {
        Map<String, List<RunErrorRow>> byCause = new LinkedHashMap<>();
        for (RunErrorRow error : errors) {
            byCause.computeIfAbsent(error.errorCode() + "|" + error.message(),
                    key -> new ArrayList<>()).add(error);
        }

        List<RunErrorGroupView> groups = new ArrayList<>();
        byCause.values().forEach(rows -> {
            RunErrorRow first = rows.getFirst();
            String field = fieldOf(first.message());
            String label = fieldLabels.getOrDefault(field, field);
            groups.add(new RunErrorGroupView(
                    titleOf(first.errorCode(), label),
                    hintOf(first.errorCode(), label, sourceOf.get(field)),
                    first.errorCode(),
                    rows.size(),
                    rangeOf(rows),
                    evidenceOf(first.sourceRow(), sourceOf.get(field)),
                    first.sourceRow()));
        });
        return groups;
    }

    private static String fieldOf(String message) {
        if (message == null) {
            return "";
        }
        Matcher matcher = ARGS.matcher(message);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /** 원인 한 줄. 코드 이름을 그대로 띄우면 «K007» 이 무엇인지 찾으러 화면을 떠난다. */
    private static String titleOf(String code, String label) {
        return switch (code) {
            case "REQUIRED_FIELD_MISSING" -> "「%s」 이(가) 비어 있습니다".formatted(label);
            case "TYPE_CONVERSION_FAILED" -> "「%s」 값을 숫자·날짜로 바꾸지 못했습니다".formatted(label);
            case "TRANSFORM_FAILED" -> "「%s」 에 건 변환 규칙이 실패했습니다".formatted(label);
            default -> "이 줄을 담지 못했습니다";
        };
    }

    /**
     * 무엇을 하면 되는지.
     *
     * <p>원인만 알려주고 끝내면 사람은 거기서 멈춘다. 특히 <b>고를 칸을 안 골랐을 때</b>와
     * <b>골랐는데 그 칸이 비었을 때</b>는 할 일이 완전히 다르다.
     */
    private static String hintOf(String code, String label, String source) {
        if (!"REQUIRED_FIELD_MISSING".equals(code)) {
            return "「칸 연결」 에서 「%s」 에 건 규칙과 고른 칸을 확인하세요.".formatted(label);
        }
        if (source == null || source.isBlank()) {
            return "「칸 연결」 에서 「%s」 에 쓸 칸을 아직 고르지 않았습니다. 골라 주세요."
                    .formatted(label);
        }
        return "「%s」 에 「%s」 칸을 쓰기로 했는데 그 칸에 값이 없습니다. 다른 칸을 골라 보세요."
                .formatted(label, source);
    }

    /** 「1–24번 줄」 처럼. 번호를 다 늘어놓으면 그것대로 읽히지 않는다. */
    private static String rangeOf(List<RunErrorRow> rows) {
        int first = rows.getFirst().rowNumber();
        int last = rows.getLast().rowNumber();
        return rows.size() == 1 ? "%d번 줄".formatted(first) : "%d–%d번 줄".formatted(first, last);
    }

    /**
     * 그 줄이 실제로 갖고 있던 값.
     *
     * <p>칸 23개를 통째로 뿌리면 사람이 중괄호를 읽어야 한다. <b>판단에 쓰이는 칸을 먼저</b>
     * 놓고 나머지는 몇 개만 붙인다 — 「내가 고른 칸에 뭐가 들어 있나」가 대개 답이다.
     */
    private static List<String> evidenceOf(String sourceRow, String source) {
        Map<String, String> values = parse(sourceRow);
        if (values.isEmpty()) {
            return List.of();
        }
        List<String> shown = new ArrayList<>();
        if (source != null && values.containsKey(source)) {
            String value = values.get(source);
            shown.add("%s = %s".formatted(source, value.isBlank() ? "(비어 있음)" : value));
        }
        values.forEach((key, value) -> {
            if (shown.size() < EVIDENCE_LIMIT && !key.equals(source) && !value.isBlank()) {
                shown.add("%s = %s".formatted(key, value));
            }
        });
        return shown;
    }

    /** 원문 JSON 에서 값만 훑는다. 파서를 들이지 않는 이유는 화면 표시에만 쓰기 때문이다. */
    private static Map<String, String> parse(String sourceRow) {
        Map<String, String> values = new LinkedHashMap<>();
        if (sourceRow == null) {
            return values;
        }
        Matcher matcher = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(sourceRow);
        while (matcher.find()) {
            values.put(matcher.group(1), matcher.group(2));
        }
        return values;
    }
}
