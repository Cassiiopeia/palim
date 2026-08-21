package kr.suhsaechan.palim.web.connector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.web.connector.ConnectorQueryService.LandedResult;

/**
 * 실행이 <b>담은 것</b>을 그리는 표.
 *
 * <p>시험 실행과 실제 적재는 담는 자리가 다르다 — 시험은 결과 보관함에, 실제는 표준 모델
 * 표에 바로 쓴다. 그 차이가 화면까지 올라오면 「담길 모습」 과 「담긴 모습」 이 서로 다른 표가
 * 되고, 같은 질문(무엇이 담겼나)에 두 가지 답이 생긴다.
 *
 * <p>예전에는 화면이 결과 보관함만 알았다. 그래서 실제 적재는 45 건이 멀쩡히 들어가고도
 * <b>「보여줄 내역이 없습니다」</b> 로 보였다 — 화면이 거짓말을 한 셈이라, 담긴 것을 확인할
 * 방법이 없다고 판단하고 화면을 떠나게 된다.
 *
 * <p>여기서 오는 곳을 하나로 흡수한다. 화면은 표 하나만 안다.
 */
public final class RunResultTable {

    private final List<Column> columns;
    private final List<Row> rows;

    private RunResultTable(List<Column> columns, List<Row> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    /**
     * 표의 칸 하나.
     *
     * @param key   담긴 칸 이름. 화면에서 「어느 칸인지」 를 정확히 짚을 때 쓴다
     * @param label 사람이 읽는 이름. 없으면 {@code key} 를 그대로 쓴다
     */
    public record Column(String key, String label) {
    }

    /**
     * 표의 줄 하나.
     *
     * @param raw 접어 둔 원문. 시험은 저장된 값 전체, 실제는 표준에 없어 따로 모아 둔 값
     */
    public record Row(int rowNumber, List<String> values, String raw) {
    }

    public List<Column> getColumns() {
        return columns;
    }

    public List<Row> getRows() {
        return rows;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** 접어 둘 원문이 하나라도 있는가. 없으면 화면에서 그 자리를 아예 만들지 않는다. */
    public boolean hasRaw() {
        return rows.stream().anyMatch(row -> row.raw() != null && !row.raw().isBlank());
    }

    /**
     * 실제로 담긴 행을 표로.
     *
     * <p>칸 순서는 조회가 정한 순서(칸 연결 순서)를 그대로 따른다. 연결 화면에서 위에 있던
     * 칸이 여기서도 왼쪽에 오지 않으면, 같은 것을 두 화면에서 두 번 익혀야 한다.
     */
    public static RunResultTable ofLanded(LandedResult landed, Map<String, String> labels) {
        List<Column> columns = landed.columns().stream()
                .map(key -> new Column(key, label(labels, key)))
                .toList();
        List<Row> rows = landed.rows().stream()
                .map(row -> new Row(row.rowNumber(), row.values(), meaningful(row.attributes())))
                .toList();
        return new RunResultTable(columns, rows);
    }

    /** 시험 결과를 표로. 값을 읽는 규칙은 {@link StagingTableView} 가 갖고 있다. */
    public static RunResultTable ofStaging(List<StagingRow> staging, Map<String, String> labels) {
        List<String> keys = StagingTableView.columnsOf(staging);
        List<Column> columns = keys.stream()
                .map(key -> new Column(key, label(labels, key)))
                .toList();

        List<Row> rows = new ArrayList<>();
        for (StagingRow source : staging) {
            Map<String, String> values = StagingTableView.valuesOf(source);
            rows.add(new Row(source.rowNumber(),
                    keys.stream().map(key -> values.getOrDefault(key, "")).toList(),
                    source.payload()));
        }
        return new RunResultTable(columns, List.copyOf(rows));
    }

    private static String label(Map<String, String> labels, String key) {
        String label = labels.get(key);
        return label == null || label.isBlank() ? key : label;
    }

    /** 빈 껍데기({@code {}})는 접을 것이 없다. 열어 봐야 빈 화면이면 접는 자리가 소음이다. */
    private static String meaningful(String json) {
        if (json == null) {
            return null;
        }
        String trimmed = json.trim();
        return trimmed.isEmpty() || "{}".equals(trimmed) ? null : trimmed;
    }
}
