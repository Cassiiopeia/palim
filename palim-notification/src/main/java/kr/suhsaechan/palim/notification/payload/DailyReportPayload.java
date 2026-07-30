package kr.suhsaechan.palim.notification.payload;

import java.time.LocalDate;
import java.util.List;

/**
 * 일일 요약 리포트 내용 (F-06).
 *
 * <p>모든 수치는 데이터베이스 조회와 코드 연산으로 산출된다. 언어 모델은 이 값을 문장으로
 * 바꾸기만 하며(F-10) <b>계산하지 않는다</b> — 재고·매출 수치가 잘못 전달되면 발주 판단에
 * 직접적인 손실을 초래하기 때문이다.
 *
 * @param date           집계 대상 날짜
 * @param totalOrderCount 총 주문 수
 * @param totalAmount    총 매출(원)
 * @param channels       채널별 실적
 * @param topSkus        판매 상위 SKU
 * @param lowStockCount  재고 부족 건수
 * @param unmappedCount  미매핑 상품 건수
 * @param failedChannels 수집 실패 채널명
 */
public record DailyReportPayload(
        LocalDate date,
        int totalOrderCount,
        long totalAmount,
        List<ChannelSummary> channels,
        List<TopSku> topSkus,
        int lowStockCount,
        int unmappedCount,
        List<String> failedChannels
) {

    public DailyReportPayload {
        channels = channels != null ? List.copyOf(channels) : List.of();
        topSkus = topSkus != null ? List.copyOf(topSkus) : List.of();
        failedChannels = failedChannels != null ? List.copyOf(failedChannels) : List.of();
    }

    /** 확인이 필요한 항목이 있는지. 없으면 리포트에 경고 섹션을 넣지 않는다. */
    public boolean hasWarnings() {
        return lowStockCount > 0 || unmappedCount > 0 || !failedChannels.isEmpty();
    }

    public record ChannelSummary(String channelName, int orderCount, long amount) {
    }

    public record TopSku(String skuCode, String productName, int quantity) {
    }
}
