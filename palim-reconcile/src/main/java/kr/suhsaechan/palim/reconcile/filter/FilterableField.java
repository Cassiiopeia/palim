package kr.suhsaechan.palim.reconcile.filter;

/**
 * 조건을 걸 수 있는 칸 하나.
 *
 * @param key            저장되는 이름. {@code warehouse_code} 또는 {@code attributes.«키»}
 * @param label          화면에 보여줄 말. 이 화면을 쓰는 사람은 개발자가 아니다
 * @param type           값 종류. 쓸 수 있는 연산자와 화면 위젯이 이것으로 갈린다
 * @param sqlExpression  별칭 없는 SQL 표현식. 별칭은 조립하는 쪽이 붙인다
 * @param fromAttributes 표준 칸이 아니라 원천 고유 칸인가. 화면이 구분해 보여준다
 */
public record FilterableField(String key, String label, FieldType type,
                              String sqlExpression, boolean fromAttributes) {

    /** 별칭을 붙인 표현식. {@code s.warehouse_code} · {@code s.attributes->>'재고구분'} */
    public String sqlWith(String alias) {
        return "%s.%s".formatted(alias, sqlExpression);
    }
}
