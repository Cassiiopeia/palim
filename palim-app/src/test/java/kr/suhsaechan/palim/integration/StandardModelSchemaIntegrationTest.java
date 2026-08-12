package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 표준 모델 스키마 검증.
 *
 * <p>이름 충돌과 자연키는 사람이 눈으로 못 잡는다. 동결 도메인과 같은 이름을 쓰면 마이그레이션이
 * 통째로 실패하고, 자연키가 NULL 을 다르게 취급하면 재실행마다 중복 행이 조용히 쌓인다.
 */
class StandardModelSchemaIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @DisplayName("동결 도메인의 stock_movement 와 표준 모델이 공존한다")
    void 동결_도메인과_이름이_충돌하지_않는다() {
        int frozen = tableCount("stock_movement");
        int standard = tableCount("std_stock_movement");

        assertThat(frozen).as("동결 도메인 테이블은 그대로 있어야 한다").isEqualTo(1);
        assertThat(standard).as("표준 모델 테이블이 따로 생겨야 한다").isEqualTo(1);
    }

    @Test
    @DisplayName("표준 모델 4종이 target_model 에 등록된다")
    void 표준_모델이_등록된다() {
        // 다른 통합 테스트가 같은 컨테이너에 BUILTIN 모델을 남기므로 전체 개수로는 셀 수 없다.
        List<String> codes = jdbcClient.sql("""
                        SELECT code FROM target_model
                        WHERE kind = 'BUILTIN'
                          AND code IN ('std_item', 'std_stock_snapshot',
                                       'std_stock_movement', 'std_outbound_order')
                        ORDER BY code
                        """)
                .query(String.class).list();

        assertThat(codes).containsExactly(
                "std_item", "std_outbound_order", "std_stock_movement", "std_stock_snapshot");
    }

    @Test
    @DisplayName("재고 스냅샷 자연키가 NULL 을 같은 값으로 취급한다")
    void 자연키가_NULL_을_구분하지_않는다() {
        // lot_code 가 NULL 인 두 행을 넣으면 두 번째가 막혀야 한다.
        // NULLS NOT DISTINCT 가 없으면 NULL != NULL 이라 중복이 생긴다.
        String indexDef = jdbcClient.sql("""
                        SELECT indexdef FROM pg_indexes
                        WHERE indexname = 'ux_std_stock_snapshot_natural'
                        """)
                .query(String.class).single();

        assertThat(indexDef).contains("NULLS NOT DISTINCT");
    }

    private int tableCount(String name) {
        return jdbcClient.sql("SELECT count(*)::int FROM pg_tables WHERE tablename = :name")
                .param("name", name)
                .query(Integer.class).single();
    }
}
