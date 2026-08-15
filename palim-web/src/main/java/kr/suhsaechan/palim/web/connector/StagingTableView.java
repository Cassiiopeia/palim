package kr.suhsaechan.palim.web.connector;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 시험 실행 결과를 <b>사람이 읽는 표</b>로 펼친다.
 *
 * <p>지금까지는 저장된 JSON 을 그대로 화면에 뿌렸다. {@code {"item_ref":"A0001","quantity":
 * 9451.0000000000,…}} 같은 것이 한 칸에 들어가 있어, 값이 제대로 들어갔는지 확인하려면 사람이
 * 중괄호를 읽어야 했다.
 *
 * <p>확인하라고 만든 화면인데 확인할 수 없으면 <b>확인 단계 자체가 형식이 된다.</b> 그러면 잘못된
 * 자료가 그대로 통과하고, 그 뒤 대조가 전부 그 값을 기준으로 돈다.
 */
@Slf4j
public final class StagingTableView {

    /** 숫자 표기. {@code 9451.0000000000} 을 그대로 두면 자릿수를 눈으로 셀 수 없다. */
    private static final DecimalFormat NUMBER = new DecimalFormat("#,##0.###");

    /**
     * 시각 표기. 다른 화면과 같은 형식을 쓴다(11-UI-RULES).
     *
     * <p>시각은 안에서 «1970년부터 몇 초» 로 다룬다. 그것을 그대로 뿌리면 {@code 1,786,719,600}
     * 이 되어 <b>사람이 읽을 수 없고</b>, 숫자로 보이니 수량과 구분되지도 않는다.
     */
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> columns;
    private final List<Row> rows;

    private StagingTableView(List<String> columns, List<Row> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    /** 한 줄. {@code values} 는 {@link #getColumns()} 순서와 맞춰 둔다. */
    public record Row(int rowNumber, String naturalKey, List<String> values, String raw) {
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<Row> getRows() {
        return rows;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * 스테이징 행들을 표로 만든다.
     *
     * <p>칸 목록은 <b>실제로 담긴 것들의 합집합</b>이다. 매핑에 있는 항목이라도 값이 하나도
     * 없으면 보여줄 이유가 없고, 빈 칸이 늘어서면 정작 봐야 할 값이 묻힌다.
     */
    public static StagingTableView of(List<StagingRow> staging, Set<String> momentColumns) {
        Set<String> columns = new LinkedHashSet<>();
        List<Map<String, String>> parsed = new ArrayList<>();
        Predicate<String> isMoment = momentColumns::contains;

        for (StagingRow row : staging) {
            Map<String, String> values = parse(row.payload(), isMoment);
            parsed.add(values);
            columns.addAll(values.keySet());
        }

        List<String> ordered = List.copyOf(columns);
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < staging.size(); i++) {
            StagingRow source = staging.get(i);
            Map<String, String> values = parsed.get(i);
            List<String> cells = ordered.stream()
                    .map(column -> values.getOrDefault(column, ""))
                    .toList();
            rows.add(new Row(source.rowNumber(), source.naturalKey(), cells, source.payload()));
        }
        return new StagingTableView(ordered, rows);
    }

    private static Map<String, String> parse(String payload, Predicate<String> isMoment) {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            JsonNode node = MAPPER.readTree(payload == null ? "{}" : payload);
            node.properties().forEach(entry -> values.put(entry.getKey(),
                    readable(entry.getValue(), isMoment.test(entry.getKey()))));
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
     * <p>중첩 객체({@code attributes})는 통째로 펼치지 않는다. 표가 옆으로 끝없이 늘어나
     * 정작 봐야 할 수량·품목이 화면 밖으로 밀린다.
     */
    private static String readable(JsonNode value, boolean moment) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isObject() || value.isArray()) {
            return value.isEmpty() ? "" : "…";
        }
        if (moment) {
            return asMoment(value);
        }
        if (value.isNumber()) {
            return NUMBER.format(value.decimalValue());
        }
        // 글자로 온 것은 글자로 둔다.
        //
        // 예전에는 숫자처럼 보이면 숫자로 바꿨다. 그래서 품목코드 «00094» 가 «94» 로 보였다 —
        // 앞자리 0 이 사라지고 천 단위 쉼표가 붙어 «01002» 는 «1,002» 가 됐다. 담긴 값은
        // 멀쩡한데 화면만 거짓말을 하는 셈이라, 확인하라고 만든 화면이 확인을 망친다.
        return value.asString();
    }

    /** «1970년부터 몇 초» 를 사람이 읽는 시각으로. 소수점이 붙어 오는 경우가 있다. */
    private static String asMoment(JsonNode value) {
        try {
            BigDecimal epochSeconds = value.isNumber()
                    ? value.decimalValue()
                    : new BigDecimal(value.asString());
            return Instant.ofEpochMilli(epochSeconds.multiply(BigDecimal.valueOf(1000)).longValue())
                    .atZone(ZoneId.systemDefault())
                    .format(MOMENT);
        } catch (RuntimeException e) {
            // 시각으로 못 읽으면 원래 값을 그대로 보여준다. 화면이 멈출 이유는 아니다.
            return value.asString();
        }
    }
}
