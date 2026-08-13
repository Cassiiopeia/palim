package kr.suhsaechan.palim.web.connector;

import java.util.List;

/**
 * 성격이 같은 항목 묶음.
 *
 * <p>재고 표준 항목은 스물아홉 개다. 한 줄로 늘어놓으면 읽히지 않아 사람이 위에서부터 훑다가
 * 포기한다. 그렇다고 숨기면 <b>있는 줄도 모르고 넘어간다</b> — 그래서 전부 보여주되 묶는다.
 *
 * @param title 묶음 이름
 * @param hint  이 묶음에 대해 알아야 할 것. 없으면 {@code null}
 * @param rows  줄들
 */
public record MappingGroupView(String title, String hint, List<MappingRowView> rows) {

    public MappingGroupView {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
