package kr.suhsaechan.palim.collector;

import java.util.List;
import kr.suhsaechan.palim.common.ChannelCode;

/**
 * 채널 1회 수집 결과 집계.
 *
 * @param channelCode        채널
 * @param outcome            수집 결과
 * @param orderCount         조회된 주문 수
 * @param newLineCount       새로 반영한 항목 수
 * @param duplicateLineCount 중복으로 건너뛴 항목 수
 * @param unmappedLineCount  미매핑 항목 수
 * @param oversoldLineCount  오버셀링 항목 수
 * @param failedOrderCount   처리에 실패한 주문 수
 * @param errorMessage       수집 자체가 실패한 경우의 오류
 */
public record CollectSummary(
        ChannelCode channelCode,
        CollectOutcome outcome,
        int orderCount,
        int newLineCount,
        int duplicateLineCount,
        int unmappedLineCount,
        int oversoldLineCount,
        int failedOrderCount,
        String errorMessage
) {

    public enum CollectOutcome {
        /** 전 주문을 처리하고 커서를 전진시켰다. */
        SUCCESS,
        /** 일부 주문 처리에 실패했다. 커서를 전진시키지 않았으므로 다음 주기에 재시도된다. */
        PARTIAL,
        /** 채널 호출 자체가 실패했다. 커서를 전진시키지 않았다. */
        FAILED,
        /** 어댑터가 아직 구현되지 않아 건너뛰었다. */
        SKIPPED
    }

    static CollectSummary skipped(ChannelCode channelCode, String reason) {
        return new CollectSummary(channelCode, CollectOutcome.SKIPPED,
                0, 0, 0, 0, 0, 0, reason);
    }

    static CollectSummary failed(ChannelCode channelCode, String errorMessage) {
        return new CollectSummary(channelCode, CollectOutcome.FAILED,
                0, 0, 0, 0, 0, 0, errorMessage);
    }

    static CollectSummary of(ChannelCode channelCode, int orderCount,
                             List<IngestResult> results, int failedOrderCount) {
        int newLines = results.stream().mapToInt(IngestResult::newLineCount).sum();
        int duplicates = results.stream().mapToInt(IngestResult::duplicateLineCount).sum();
        int unmapped = results.stream().mapToInt(IngestResult::unmappedLineCount).sum();
        int oversold = results.stream().mapToInt(IngestResult::oversoldLineCount).sum();

        CollectOutcome outcome = failedOrderCount == 0 ? CollectOutcome.SUCCESS : CollectOutcome.PARTIAL;
        return new CollectSummary(channelCode, outcome, orderCount,
                newLines, duplicates, unmapped, oversold, failedOrderCount, null);
    }

    public boolean advancedCursor() {
        return outcome == CollectOutcome.SUCCESS;
    }
}
