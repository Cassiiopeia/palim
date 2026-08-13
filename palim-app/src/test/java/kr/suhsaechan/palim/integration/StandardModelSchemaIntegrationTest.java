package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.common.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 표준 모델 스키마 검증.
 *
 * <p>이름 충돌과 자연키는 사람이 눈으로 못 잡는다. 동결 도메인과 같은 이름을 쓰면 마이그레이션이
 * 통째로 실패하고, 자연키가 빈 값을 서로 다르게 취급하면 재실행마다 중복 행이 조용히 쌓인다.
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
    @DisplayName("창고·로트가 빈 행은 재실행해도 중복되지 않는다")
    void 빈_자연키가_중복을_만들지_않는다() {
        // 창고·로트 컬럼이 없는 원천이 흔하다. 그 값들이 NULL 이면 유니크 인덱스에서
        // NULL != NULL 이라 같은 재고가 실행할 때마다 새 행으로 쌓인다.
        // 인덱스 정의 문자열이 아니라 실제 동작으로 확인한다 — 구현 방식이 바뀌어도
        // 지켜야 할 것은 "두 번째 삽입이 막힌다"는 사실이다.
        String itemRef = "dup-" + UUID.randomUUID();

        insertSnapshot(itemRef);

        assertThatThrownBy(() -> insertSnapshot(itemRef))
                .as("같은 자연키가 두 번 들어가면 재실행마다 재고가 부풀어 오른다")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("자연키 컬럼에는 NULL 을 넣을 수 없다")
    void 자연키에_NULL_을_막는다() {
        // 빈 값 표현을 빈 문자열로 통일했으므로, NULL 이 들어오는 경로가 있으면 즉시 드러나야 한다.
        // 조용히 통과시키면 그 행만 중복 방지에서 빠진다.
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source, warehouse_code, lot_code,
                             quantity, base_quantity, base_unit, created_at, updated_at)
                        VALUES (:id, :tenantId, :itemRef, :baseAt, 'TEST', NULL, '',
                                1, 1, 'EA', now(), now())
                        """)
                .param("id", UuidV7.generate())
                .param("tenantId", TenantContext.DEFAULT_TENANT_ID)
                .param("itemRef", "null-" + UUID.randomUUID())
                .param("baseAt", OffsetDateTime.of(2026, 8, 13, 0, 0, 0, 0, ZoneOffset.UTC))
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 창고·로트를 지정하지 않는다 — 기본값(빈 문자열)이 들어간다. */
    private void insertSnapshot(String itemRef) {
        jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source,
                             quantity, base_quantity, base_unit, created_at, updated_at)
                        VALUES (:id, :tenantId, :itemRef, :baseAt, 'TEST',
                                1, 1, 'EA', now(), now())
                        """)
                .param("id", UuidV7.generate())
                .param("tenantId", TenantContext.DEFAULT_TENANT_ID)
                .param("itemRef", itemRef)
                .param("baseAt", OffsetDateTime.of(2026, 8, 13, 0, 0, 0, 0, ZoneOffset.UTC))
                .update();
    }

    private int tableCount(String name) {
        return jdbcClient.sql("SELECT count(*)::int FROM pg_tables WHERE tablename = :name")
                .param("name", name)
                .query(Integer.class).single();
    }
}
