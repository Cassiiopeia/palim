package kr.suhsaechan.palim.collector;

import kr.suhsaechan.palim.common.ChannelCode;

/**
 * 주문 1건 수집 처리 결과.
 *
 * @param channelCode        채널
 * @param channelOrderNo     채널 주문번호
 * @param newLineCount       새로 저장하고 재고를 반영한 항목 수
 * @param duplicateLineCount 이미 수집되어 건너뛴 항목 수
 * @param unmappedLineCount  매핑이 없어 재고를 반영하지 못한 항목 수
 * @param oversoldLineCount  차감 결과 재고가 음수가 된 항목 수
 */
public record IngestResult(
        ChannelCode channelCode,
        String channelOrderNo,
        int newLineCount,
        int duplicateLineCount,
        int unmappedLineCount,
        int oversoldLineCount
) {

    public static IngestResult of(ChannelCode channelCode, String channelOrderNo,
                                  int newLineCount, int duplicateLineCount,
                                  int unmappedLineCount, int oversoldLineCount) {
        return new IngestResult(channelCode, channelOrderNo, newLineCount,
                duplicateLineCount, unmappedLineCount, oversoldLineCount);
    }

    /** 전부 중복이었는지 — 겹침 수집 구간에서는 이게 정상이다. */
    public boolean isAllDuplicate() {
        return newLineCount == 0 && unmappedLineCount == 0 && duplicateLineCount > 0;
    }

    public boolean hasUnmapped() {
        return unmappedLineCount > 0;
    }

    public boolean hasOversold() {
        return oversoldLineCount > 0;
    }
}
