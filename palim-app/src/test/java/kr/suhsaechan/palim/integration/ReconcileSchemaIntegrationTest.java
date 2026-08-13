package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 대조 테이블이 PostgreSQL 14 에서 만들어지는가.
 *
 * <p>운영 DB 가 14 이고 상위 버전 전용 문법은 <b>배포에서만</b> 죽는다. 실제로
 * {@code NULLS NOT DISTINCT}(15+) 로 한 번 겪었다 — 로컬에서 멀쩡히 돌다가 배포에서 터졌다.
 */
class ReconcileSchemaIntegrationTest extends IntegrationTest {

    @Autowired private JdbcClient jdbcClient;

    @Test
    @DisplayName("대조 테이블 여섯 개가 만들어진다")
    void 테이블이_만들어진다() {
        // count(*) 는 bigint 라 record 가 int 면 캐스팅해야 한다.
        int found = jdbcClient.sql("""
                        SELECT count(*)::int FROM information_schema.tables
                         WHERE table_name IN ('reconcile_unit','reconcile_unit_member',
                               'normalization_rule','reconcile_definition',
                               'reconcile_run','reconcile_diff')
                        """)
                .query(Integer.class).single();

        assertThat(found).isEqualTo(6);
    }

    /**
     * 한 품목이 두 단위에 붙으면 그 수량이 두 번 세어지고 <b>대조 결과가 조용히 틀린다.</b>
     * 화면 검증만으로는 동시 요청에서 뚫리므로 DB 가 막아야 한다.
     */
    @Test
    @DisplayName("같은 원천 품목은 한 단위에만 속한다")
    void 품목_중복_연결을_막는다() {
        int unique = jdbcClient.sql("""
                        SELECT count(*)::int FROM pg_indexes
                         WHERE tablename = 'reconcile_unit_member'
                           AND indexdef LIKE '%UNIQUE%'
                           AND indexdef LIKE '%source%'
                           AND indexdef LIKE '%item_ref%'
                        """)
                .query(Integer.class).single();

        assertThat(unique).isGreaterThanOrEqualTo(1);
    }

    /**
     * 확정 시각은 비어 있을 수 있어야 한다. 제안 상태를 행으로 남기되 대조에는 쓰지 않기
     * 위해서다 — 사람이 확인하지 않은 추측으로 재고를 합산하면 결과가 맞는지 아무도 모른다.
     */
    @Test
    @DisplayName("확정 시각은 비어 있을 수 있다")
    void 제안_상태를_담을_수_있다() {
        String nullable = jdbcClient.sql("""
                        SELECT is_nullable FROM information_schema.columns
                         WHERE table_name = 'reconcile_unit_member'
                           AND column_name = 'confirmed_at'
                        """)
                .query(String.class).single();

        assertThat(nullable).isEqualTo("YES");
    }
}
