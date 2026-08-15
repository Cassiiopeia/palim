package kr.suhsaechan.palim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import kr.suhsaechan.palim.common.BaseAtGranularity;
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
    /** 코드가 「하루」 를 가르는 지역과 같은 값이어야 한다 — 다르면 이 시험은 아무것도 안 지킨다. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

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

    /**
     * 「오늘」 이 흔들리지 않게 <b>고정된 날짜</b>의 그 시각을 만든다.
     *
     * <p>{@code Instant.now()} 로 오전·오후를 잡으면 자정 근처에 돌릴 때 두 시각이 다른 날로
     * 갈라져 테스트가 하루에 한 번 실패한다 — 원인을 못 찾는 종류의 실패다.
     */
    private static Instant at(int hour, int minute) {
        return LocalDate.of(2026, 3, 5).atTime(hour, minute).atZone(BUSINESS_ZONE).toInstant();
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

        var aligned = resolver.resolve(TENANT, erp, wms, BaseAtGranularity.DAY);

        assertThat(aligned.left().truncatedTo(ChronoUnit.SECONDS)).isEqualTo(now);
        assertThat(aligned.right().truncatedTo(ChronoUnit.SECONDS)).isEqualTo(now);
    }

    @Test
    @DisplayName("시각이 다르면 비교를 거부한다")
    void 시각이_다르면_거부한다() {
        snapshot(erp, now);
        snapshot(wms, now.minus(1, ChronoUnit.DAYS));

        assertThatThrownBy(() -> resolver.resolve(TENANT, erp, wms, BaseAtGranularity.DAY))
                .as("그 사이 출고분만큼 무조건 차이가 나므로, 비교하면 진짜 차이를 가린다")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(
                        ((BusinessException) e).is(ErrorCode.RECONCILE_BASE_AT_MISMATCH)).isTrue());
    }

    /**
     * <b>여기가 이 기능의 핵심이다.</b>
     *
     * <p>전산은 기준일을 날짜로만 주고 물류는 「지금 재고」 를 준다. 그래서 두 시각이 정확히
     * 같은 일은 사실상 없다. 「정확히 같아야 한다」 로 두면 대조는 <b>영원히 안 돈다</b> —
     * 화면에는 매일 「기준 시각이 다릅니다」 만 쌓이고, 사람은 시스템이 고장 났다고 여긴다.
     */
    @Test
    @DisplayName("하루 눈금이면 같은 날 다른 시각이어도 견준다")
    void 같은_날이면_시각이_달라도_견준다() {
        Instant morning = at(9, 0);
        Instant afternoon = at(14, 30);
        snapshot(erp, morning);
        snapshot(wms, afternoon);

        var aligned = resolver.resolve(TENANT, erp, wms, BaseAtGranularity.DAY);

        assertThat(aligned.left().truncatedTo(ChronoUnit.SECONDS))
                .as("합산은 각 원천이 «실제로 가진» 시각으로 해야 한다. 칸 시작 시각에는 자료가 없다")
                .isEqualTo(morning.truncatedTo(ChronoUnit.SECONDS));
        assertThat(aligned.right().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(afternoon.truncatedTo(ChronoUnit.SECONDS));
        assertThat(aligned.bucket())
                .as("실행 기록에 남는 것은 «칸» 이다 — 그래야 이력이 하루 한 줄로 이어진다")
                .isEqualTo(BaseAtGranularity.DAY.truncate(morning));
    }

    /**
     * 굵게 볼 수 있다는 것이 <b>아무렇게나 본다</b>는 뜻은 아니다. 촘촘히 담아 두고 시간 단위로
     * 견주기로 했다면, 다른 시간대끼리는 여전히 거부해야 한다 — 그 사이 출고분이 그대로 차이로
     * 나타나기 때문이다.
     */
    @Test
    @DisplayName("시간 눈금이면 같은 날이어도 시간이 다르면 거부한다")
    void 시간_눈금은_시간이_다르면_거부한다() {
        snapshot(erp, at(9, 10));
        snapshot(wms, at(14, 30));

        assertThatThrownBy(() -> resolver.resolve(TENANT, erp, wms, BaseAtGranularity.HOUR))
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

        assertThatThrownBy(() -> resolver.resolve(TENANT, erp, wms, BaseAtGranularity.DAY))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(
                        ((BusinessException) e).is(ErrorCode.RECONCILE_SNAPSHOT_MISSING)).isTrue());
    }
}
