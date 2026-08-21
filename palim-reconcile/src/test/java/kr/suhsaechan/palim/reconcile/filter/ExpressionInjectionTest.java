package kr.suhsaechan.palim.reconcile.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import kr.suhsaechan.palim.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 위험한 것은 <b>막는 게 아니라 문법에 없다.</b>
 *
 * <p>금지어 목록으로 막는 방식은 막을 것을 전부 알고 있어야 성립하고, 그래서 언젠가 뚫린다.
 * 여기 적힌 것들이 실패하는 이유는 「위험해서 걸렀다」 가 아니라 <b>문법이 그것을 표현하지
 * 못해서</b> 다 — 함수 호출·서브쿼리·세미콜론·주석이 규칙에 아예 없다.
 *
 * <p>설령 파서에 구멍이 있어 무엇이 통과해도, 통과한 것은 {@link FilterNode} 가 되고 그
 * 노드가 만들 수 있는 SQL 은 {@code FilterNode.Compare#appendTo} 에 적힌 틀뿐이다.
 */
class ExpressionInjectionTest {

    private static final Instant AS_OF = Instant.parse("2026-08-22T03:00:00Z");

    @ParameterizedTest
    @ValueSource(strings = {
            "창고 = '01'; DROP TABLE std_stock_snapshot",
            "창고 = '01' -- 나머지는 주석",
            "창고 = '01' /* 주석 */ 또는 1=1",
            "창고 = '01' UNION SELECT 1",
            "창고 = (SELECT max(id) FROM std_stock_snapshot)",
            "lower(창고) = '01'",
            "pg_sleep(10) = 1",
            "tenant_id = '00000000-0000-0000-0000-000000000000'",
            "1 = 1",
            "창고 = '01' 또는 '1'='1'",
            "\"warehouse_code\" = '01'",
            "s.warehouse_code = '01'",
            "std_stock_snapshot.warehouse_code = '01'",
            "창고 = 01",
            "COPY std_stock_snapshot TO '/tmp/x'",
            "창고 = '01' 그리고 pg_read_file('/etc/passwd') = 'x'",
            "창고 = '01') OR (1=1",
    })
    @DisplayName("읽지 못한다 — 문법에 그런 것이 없기 때문이다")
    void refusesToRead(String payload) {
        assertThatThrownBy(() -> ExpressionParser.parse(payload))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "'); DROP TABLE std_stock_snapshot; --",
            "' OR '1'='1",
            "; DELETE FROM reconcile_run; --",
    })
    @DisplayName("값 자리에 든 주입 시도는 값일 뿐이다 — 바인딩되어 SQL 이 되지 않는다")
    void payloadInValuePositionStaysAValue(String payload) {
        String text = "창고 = '" + payload.replace("'", "''") + "'";

        String sql = new FilterSpec(ExpressionParser.parse(text)).sqlAnd("s", "f", AS_OF);

        assertThat(sql)
                .isEqualTo(" AND (s.warehouse_code = :f0)")
                .doesNotContain("DROP").doesNotContain("DELETE").doesNotContain("--");
    }

    @Test
    @DisplayName("길이·깊이·노드 수 상한을 넘으면 거부한다 — 파싱은 되어도 비싼 것은 만들 수 있다")
    void refusesOversized() {
        assertThatThrownBy(() -> ExpressionParser.parse("창고 = '01' 그리고 ".repeat(400)
                + "창고 = '01'"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ExpressionParser.parse("(".repeat(50)
                + "창고 = '01'" + ")".repeat(50)))
                .isInstanceOf(BusinessException.class);
    }
}
