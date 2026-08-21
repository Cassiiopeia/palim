package kr.suhsaechan.palim.reconcile.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 걸 수 있는 칸의 목록.
 *
 * <p>카탈로그를 두는 이유는 «사용자가 칸 이름을 정하지 않게» 하기 위해서다. 자유 입력이면
 * 오타가 저장되고 대조가 돌 때 터진다 — 매일 아침 스스로 도는 일에서 그것은 「어제까지 되던
 * 것이 오늘 죽었다」 로만 드러난다.
 */
class FieldCatalogTest {

    @Test
    @DisplayName("표준 칸은 이름으로 찾을 수 있고 SQL 표현식을 안다")
    void findsStandardField() {
        FilterableField field = FieldCatalog.find("warehouse_code").orElseThrow();

        assertThat(field.label()).isEqualTo("창고");
        assertThat(field.type()).isEqualTo(FieldType.TEXT);
        assertThat(field.sqlWith("s")).isEqualTo("s.warehouse_code");
        assertThat(field.fromAttributes()).isFalse();
    }

    @Test
    @DisplayName("유통기한은 날짜 칸이다 — 날짜 연산자만 쓸 수 있어야 한다")
    void expiryDateIsDate() {
        assertThat(FieldCatalog.find("expiry_date").orElseThrow().type())
                .isEqualTo(FieldType.DATE);
    }

    @Test
    @DisplayName("attributes 안의 원천 고유 칸도 걸 수 있다")
    void findsAttributeField() {
        FilterableField field = FieldCatalog.find("attributes.재고구분").orElseThrow();

        assertThat(field.type()).isEqualTo(FieldType.TEXT);
        assertThat(field.sqlWith("s")).isEqualTo("s.attributes->>'재고구분'");
        assertThat(field.fromAttributes()).isTrue();
    }

    @Test
    @DisplayName("카탈로그에 없는 이름은 찾지 못한다 — 이것이 인젝션 방어의 첫 겹이다")
    void rejectsUnknownField() {
        assertThat(FieldCatalog.find("tenant_id")).isEmpty();
        assertThat(FieldCatalog.find("id")).isEmpty();
        assertThat(FieldCatalog.find("1=1")).isEmpty();
        assertThat(FieldCatalog.find("")).isEmpty();
        assertThat(FieldCatalog.find(null)).isEmpty();
    }

    @Test
    @DisplayName("attributes 키에 따옴표가 섞이면 거부한다 — 표현식에 그대로 들어가는 자리다")
    void rejectsQuoteInAttributeKey() {
        assertThat(FieldCatalog.find("attributes.a'b")).isEmpty();
        assertThat(FieldCatalog.find("attributes.a\"b")).isEmpty();
        assertThat(FieldCatalog.find("attributes.a\\b")).isEmpty();
        assertThat(FieldCatalog.find("attributes.")).isEmpty();
    }
}
