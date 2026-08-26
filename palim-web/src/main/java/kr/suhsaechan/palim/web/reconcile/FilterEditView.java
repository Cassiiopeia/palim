package kr.suhsaechan.palim.web.reconcile;

import java.util.List;
import java.util.Map;
import kr.suhsaechan.palim.reconcile.engine.SnapshotAggregator;
import kr.suhsaechan.palim.reconcile.filter.FilterRow;
import kr.suhsaechan.palim.reconcile.filter.FilterSide;
import kr.suhsaechan.palim.reconcile.filter.FilterableField;

/**
 * 한쪽 원천의 조건 편집기에 필요한 것 전부.
 *
 * <p>화면이 «네 가지를 따로 모델에 담는» 대신 한 묶음으로 받는다. 좌·우 두 벌이라 따로 담으면
 * 이름이 여덟 개가 되고, 그중 하나를 빠뜨려도 화면은 조용히 빈 칸을 그린다.
 *
 * @param side          어느 쪽인가
 * @param source        그 원천 이름. 화면이 어느 쪽을 고치는지 보여준다
 * @param rows          지금 걸린 조건 줄
 * @param expression    지금 걸린 식. 없으면 빈 문자열
 * @param fields        걸 수 있는 칸. 표준 칸 + 이 원천이 실제로 주는 고유 칸
 * @param valuesByField 칸별 값 후보. 글 칸만 담긴다 — 숫자·날짜는 목록이 뜻이 없다
 * @param preview       지금 조건이면 몇 줄이 남는지
 */
public record FilterEditView(FilterSide side, String source,
                             List<FilterRow> rows, String expression,
                             List<FilterableField> fields,
                             Map<String, List<SnapshotAggregator.FieldValue>> valuesByField,
                             SnapshotAggregator.Preview preview) {

    /** 담긴 자료가 아예 없다. 「고를 것이 없다」 와 「다 골랐다」 는 다른 사정이다. */
    public boolean isEmpty() {
        return preview.totalItems() == 0;
    }

    /** 아무 조건도 안 걸렸는가. */
    public boolean hasNoFilter() {
        return rows.isEmpty() && expression.isBlank();
    }

    /**
     * 창고가 여럿인데 아무 조건도 안 건 상태인가.
     *
     * <p>그대로 두면 맡기지 않은 물량까지 합산되어 <b>조용히 틀린 답</b>이 나온다. 화면이
     * 이때 경고를 띄운다.
     */
    public boolean needsChoice() {
        return hasNoFilter()
                && valuesByField.getOrDefault("warehouse_code", List.of()).size() > 1;
    }

    /**
     * 칸의 사람 이름. 없으면 칸 키를 그대로 쓴다.
     *
     * <p>값 목록의 제목이 {@code warehouse_code} 이면, 고르는 사람이 조건 칸의 「창고」 와
     * 같은 것인지 확신하지 못한다. 같은 것은 같은 이름으로 부른다.
     */
    public String labelOf(String fieldKey) {
        return fields.stream()
                .filter(field -> field.key().equals(fieldKey))
                .map(FilterableField::label)
                .findFirst()
                .orElse(fieldKey);
    }

    /**
     * 그 값이 지금 조건에 들어 있는가. 체크 상태를 되살린다.
     *
     * <p>저장하고 돌아왔을 때 체크가 풀려 있으면 <b>저장이 안 된 줄 안다.</b> 그러면 같은 것을
     * 다시 고르고, 이미 걸린 조건에 같은 값이 한 번 더 붙는다.
     */
    public boolean picked(String fieldKey, String value) {
        return rows.stream()
                .filter(row -> !row.isExpression())
                .filter(row -> fieldKey.equals(row.getFieldKey()))
                .anyMatch(row -> row.getValues() != null && row.getValues().contains(value));
    }

    /**
     * 골라서 걸 수 있는 값인가.
     *
     * <p>빈 값은 고를 수 없다. 조건에 넣어도 <b>아무것도 거르지 않는데</b> 화면은 「걸렸다」 고
     * 보인다 — 창고를 안 주는 원천이 여기 해당한다(그쪽은 전부가 곧 한 창고다).
     */
    public boolean pickable(SnapshotAggregator.FieldValue value) {
        return !value.value().isBlank();
    }

    /**
     * 값 후보를 다 못 보여주는 칸인가.
     *
     * <p>품목코드처럼 값이 수만 개인 칸이 있다. <b>말없이 자르지 않는다</b> — 목록에 없는 값은
     * 없는 값으로 읽힌다.
     */
    public boolean truncated(String fieldKey) {
        return valuesByField.getOrDefault(fieldKey, List.of()).size()
                > SnapshotAggregator.VALUE_LIMIT;
    }
}
