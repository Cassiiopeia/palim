package kr.suhsaechan.palim.web.connector;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 시험 실행이 저장한 JSON 을 <b>사람이 읽는 값</b>으로 편다.
 *
 * <p>예전에는 저장된 JSON 을 그대로 화면에 뿌렸다. {@code {"item_ref":"A0001","quantity":
 * 9451.0000000000,…}} 같은 것이 한 칸에 들어가 있어, 값이 제대로 들어갔는지 확인하려면 사람이
 * 중괄호를 읽어야 했다.
 *
 * <p>확인하라고 만든 화면인데 확인할 수 없으면 <b>확인 단계 자체가 형식이 된다.</b> 그러면 잘못된
 * 자료가 그대로 통과하고, 그 뒤 대조가 전부 그 값을 기준으로 돈다.
 *
 * <p>표를 만드는 일은 {@link RunResultTable} 이 한다. 여기 남은 것은 <b>값 하나를 어떻게
 * 읽을 것인가</b> 뿐이다 — 시험과 실제가 표는 같아도 저장된 모양이 다르기 때문이다.
 */
@Slf4j
public final class StagingTableView {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StagingTableView() {
    }

    /** 시험 결과를 표로. 칸의 사람 이름이 필요하면 {@link RunResultTable#ofStaging} 을 쓴다. */
    public static RunResultTable of(List<StagingRow> staging) {
        return RunResultTable.ofStaging(staging, Map.of());
    }

    /**
     * 표에 세울 칸.
     *
     * <p><b>실제로 담긴 것들의 합집합</b>이다. 연결에 있는 항목이라도 값이 하나도 없으면 보여줄
     * 이유가 없고, 빈 칸이 늘어서면 정작 봐야 할 값이 묻힌다.
     */
    static List<String> columnsOf(List<StagingRow> staging) {
        Set<String> columns = new LinkedHashSet<>();
        staging.forEach(row -> columns.addAll(valuesOf(row).keySet()));
        return List.copyOf(columns);
    }

    /** 한 줄의 값. 칸 이름 → 읽을 수 있는 값. */
    static Map<String, String> valuesOf(StagingRow row) {
        Map<String, String> values = new LinkedHashMap<>();
        String payload = row.payload();
        try {
            JsonNode node = MAPPER.readTree(payload == null ? "{}" : payload);
            node.properties().forEach(entry -> values.put(entry.getKey(),
                    readable(entry.getValue())));
        } catch (RuntimeException e) {
            // 읽지 못해도 화면은 열려야 한다. 원문을 그대로 한 칸에 넣어 사람이 볼 수 있게 둔다.
            log.warn("시험 결과를 표로 펼치지 못했다. 원문을 그대로 보여준다.", e);
            values.put("(원문)", payload == null ? "" : payload);
        }
        return values;
    }

    /**
     * 값 하나를 읽을 수 있게 만든다.
     *
     * <p><b>화면은 값을 손대지 않는다.</b> 여기는 「진짜로 넣기 전에 눈으로 보는」 자리라,
     * 보이는 것과 담긴 것이 다르면 확인이 거짓이 된다. 읽기 좋게 만드는 일은 담을 때 한다.
     *
     * <p>중첩 객체({@code attributes})는 통째로 펼치지 않는다. 표가 옆으로 끝없이 늘어나
     * 정작 봐야 할 수량·품목이 화면 밖으로 밀린다.
     */
    private static String readable(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        // 중첩 객체만 예외다. 통째로 펼치면 표가 옆으로 끝없이 늘어나 정작 봐야 할 수량·품목이
        // 화면 밖으로 밀린다.
        if (value.isObject() || value.isArray()) {
            return value.isEmpty() ? "" : "…";
        }
        // 그 밖에는 받은 값을 그대로 보여준다.
        //
        // 예전에는 숫자처럼 «생긴» 값을 숫자로 다듬었다. 그래서 품목코드 «00094» 가 «94» 로,
        // «01002» 가 «1,002» 로 보였다. 담긴 것은 멀쩡한데 화면만 다르게 말하는 셈이라,
        // 진짜로 넣기 전에 눈으로 보라고 만든 자리가 오히려 판단을 망쳤다.
        return value.asString();
    }
}
