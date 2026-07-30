package kr.suhsaechan.palim.monitor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import kr.suhsaechan.palim.notification.payload.DailyReportPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일일 실적을 집계한다 (F-06).
 *
 * <h2>모든 수치는 SQL 과 코드 연산으로 산출한다</h2>
 *
 * <p>이것이 F-10(AI 리포트 요약)의 전제다. 언어 모델은 <b>이미 확정된 수치를 문장으로 바꾸는
 * 역할만</b> 하며 판매량·매출·증감률을 계산하지 않는다. 언어 모델은 산술에서 오류를 내며, 재고와
 * 매출 수치가 잘못 전달되면 발주 판단에 직접적인 손실을 초래한다.
 *
 * <h2>JdbcClient 를 쓰는 이유</h2>
 *
 * <p>주문·SKU·채널을 함께 집계해야 하는데 도메인 모듈이 분리되어 JPA 로 조인할 수 없다.
 * 02-ARCHITECTURE 규칙 3에 따라 조회는 직접 SQL 로 처리하고 결과를 {@code record} 로 받는다.
 *
 * <h2>날짜 경계는 KST 다</h2>
 *
 * <p>저장은 {@code timestamptz}(UTC)지만 발주자가 인지하는 "어제"는 KST 기준이다. UTC 자정으로
 * 자르면 <b>한국 시간 오전 9시까지의 주문이 전일로 집계되어</b> 수치가 실제와 어긋난다.
 */
@Component
@RequiredArgsConstructor
public class DailyReportAssembler {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final JdbcClient jdbcClient;
    private final MonitorProperties monitorProperties;

    /**
     * 지정 날짜(KST)의 실적을 집계한다.
     *
     * @param targetDate 집계 대상 날짜. 보통 전일
     */
    @Transactional(readOnly = true)
    public DailyReportPayload assemble(LocalDate targetDate) {
        Instant from = targetDate.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant to = targetDate.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();

        Totals totals = queryTotals(from, to);

        return new DailyReportPayload(
                targetDate,
                totals.orderCount(),
                totals.amount(),
                queryChannelSummaries(from, to),
                queryTopSkus(from, to),
                countLowStock(),
                countUnmappedLines(),
                queryFailedChannels());
    }

    private Totals queryTotals(Instant from, Instant to) {
        return jdbcClient.sql("""
                        select count(*)                       as order_count,
                               coalesce(sum(total_amount), 0) as amount
                        from orders
                        where ordered_at >= :from and ordered_at < :to
                          and status = 'PLACED'
                        """)
                .param("from", from)
                .param("to", to)
                .query(Totals.class)
                .single();
    }

    private List<DailyReportPayload.ChannelSummary> queryChannelSummaries(Instant from, Instant to) {
        return jdbcClient.sql("""
                        select channel_code                   as channel_name,
                               count(*)                       as order_count,
                               coalesce(sum(total_amount), 0) as amount
                        from orders
                        where ordered_at >= :from and ordered_at < :to
                          and status = 'PLACED'
                        group by channel_code
                        order by amount desc
                        """)
                .param("from", from)
                .param("to", to)
                .query(DailyReportPayload.ChannelSummary.class)
                .list();
    }

    /**
     * 판매 상위 SKU.
     *
     * <p>미매핑 항목은 제외한다. {@code sku_id} 가 null 이므로 조인 대상이 없고, 상품명을 알 수
     * 없어 리포트에 표시할 수 없다. 미매핑 건수는 별도 항목으로 알린다.
     */
    private List<DailyReportPayload.TopSku> queryTopSkus(Instant from, Instant to) {
        return jdbcClient.sql("""
                        select s.code          as sku_code,
                               s.name          as product_name,
                               sum(l.quantity) as quantity
                        from order_line l
                                 join sku s on s.id = l.sku_id
                                 join orders o on o.id = l.order_id
                        where o.ordered_at >= :from and o.ordered_at < :to
                          and o.status = 'PLACED'
                        group by s.code, s.name
                        order by quantity desc
                        limit :limit
                        """)
                .param("from", from)
                .param("to", to)
                .param("limit", monitorProperties.topSkuLimit())
                .query(DailyReportPayload.TopSku.class)
                .list();
    }

    private int countLowStock() {
        return jdbcClient.sql("""
                        select count(*) from sku
                        where active = true and quantity < safety_threshold
                        """)
                .query(Integer.class)
                .single();
    }

    private int countUnmappedLines() {
        return jdbcClient.sql("select count(*) from order_line where sku_id is null")
                .query(Integer.class)
                .single();
    }

    private List<String> queryFailedChannels() {
        return jdbcClient.sql("""
                        select name from channel
                        where last_collect_status = 'FAILED' and enabled = true
                        order by name
                        """)
                .query(String.class)
                .list();
    }

    /** 집계 결과 매핑용. */
    public record Totals(int orderCount, long amount) {
    }
}
