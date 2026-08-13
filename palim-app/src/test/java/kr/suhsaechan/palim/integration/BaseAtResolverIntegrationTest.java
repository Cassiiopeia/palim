package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.common.support.IntegrationTest;
import kr.suhsaechan.palim.reconcile.engine.BaseAtResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 기준 시각이 다른 재고는 <b>비교를 거부한다</b>.
 *
 * <p>두 재고를 다른 시각에 뽑으면 그 사이 출고분만큼 무조건 차이가 난다. 억지로 맞춰 비교하면
 * 그 차이가 진짜인지 시간 탓인지 영영 알 수 없고, 그런 결과는 몇 번 어긋나는 순간 아무도 보지
 * 않게 된다.
 *
 * <p><b>대조가 신뢰를 잃는 것이 대조가 없는 것보다 나쁘다.</b> 있는데 아무도 안 보는 화면이
 * 되면 문제가 있다는 사실 자체가 가려진다.
 */
class BaseAtResolverIntegrationTest extends IntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired private BaseAtResolver resolver;
    @Autowired private JdbcClient jdbcClient;

    private String erp;
    private String wms;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        erp = "erp-" + UUID.randomUUID().toString().substring(0, 6);
        wms = "wms-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private void snapshot(String source, Instant baseAt) {
        var at = baseAt.atOffset(ZoneOffset.UTC);
        jdbcClient.sql("""
                        INSERT INTO std_stock_snapshot
                            (id, tenant_id, item_ref, base_at, source, warehouse_code, lot_code,
                             quantity, base_quantity, base_unit, created_at, updated_at)
                        VALUES (:id, :tenant, :item, :at, :source, '', '',
                                :qty, :qty, 'EA', :at, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("item", "ITEM-" + UUID.randomUUID().toString().substring(0, 6))
                .param("at", at)
                .param("source", source)
                .param("qty", BigDecimal.ONE)
                .update();
    }

    @Test
    @DisplayName("양쪽 시각이 같으면 그 시각으로 비교한다")
    void 시각이_같으면_통과한다() {
        snapshot(erp, now);
        snapshot(wms, now);

        assertThat(resolver.resolve(TENANT, erp, wms).truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(now);
    }

    @Test
    @DisplayName("시각이 다르면 비교를 거부한다")
    void 시각이_다르면_거부한다() {
        snapshot(erp, now);
        snapshot(wms, now.minus(1, ChronoUnit.DAYS));

        assertThatThrownBy(() -> resolver.resolve(TENANT, erp, wms))
                .as("그 사이 출고분만큼 무조건 차이가 나므로, 비교하면 진짜 차이를 가린다")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(
                        ((BusinessException) e).is(ErrorCode.RECONCILE_BASE_AT_MISMATCH)).isTrue());
    }

    /**
     * 한쪽 재고가 아예 없는 것과 시각이 어긋난 것은 <b>고칠 방법이 다르다.</b> 전자는 수집을
     * 확인해야 하고 후자는 기준일을 맞춰 다시 받아야 한다. 같은 오류로 묶으면 어디를 봐야
     * 할지 알 수 없다.
     */
    @Test
    @DisplayName("한쪽에 재고가 없으면 다른 사유로 알린다")
    void 재고가_없으면_따로_알린다() {
        snapshot(erp, now);

        assertThatThrownBy(() -> resolver.resolve(TENANT, erp, wms))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(
                        ((BusinessException) e).is(ErrorCode.RECONCILE_SNAPSHOT_MISSING)).isTrue());
    }
}
