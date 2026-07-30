package kr.suhsaechan.palim.channel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채널 재고 전송 이력 (F-08).
 *
 * <p>이 기능은 채널에 데이터를 <b>기록</b>하는 유일한 경로이므로 오류 시 실제 판매에 영향을 준다.
 * 전송 시각·채널·SKU·변경 전후 값을 모두 남겨 사고 시 추적할 수 있게 한다.
 *
 * <p>시뮬레이션 모드에서도 기록한다. 실제 전송 없이 무엇이 나갈 예정이었는지 확인하는 것이
 * 도입 초기 검증의 핵심이기 때문이다.
 */
@Getter
@Entity
@Table(name = "stock_push_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockPushLog extends BaseTimeEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_code", nullable = false, length = 20)
    private ChannelCode channelCode;

    @Column(nullable = false)
    private UUID skuId;

    @Column(nullable = false, length = 100)
    private String channelProductNo;

    /** 전송 전 채널 재고. 채널에서 조회하지 못한 경우 null 이다. */
    private Integer beforeQuantity;

    @Column(nullable = false)
    private int afterQuantity;

    /** 시뮬레이션 모드 여부. true 면 실제 전송하지 않았다. */
    @Column(nullable = false)
    private boolean simulated;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockPushStatus status;

    @Column(length = 1000)
    private String errorMessage;

    @Version
    private Long version;

    @Builder(access = AccessLevel.PRIVATE)
    private StockPushLog(ChannelCode channelCode, UUID skuId, String channelProductNo,
                         Integer beforeQuantity, int afterQuantity, boolean simulated,
                         StockPushStatus status, String errorMessage) {
        this.id = UuidV7.generate();
        this.channelCode = channelCode;
        this.skuId = skuId;
        this.channelProductNo = channelProductNo;
        this.beforeQuantity = beforeQuantity;
        this.afterQuantity = afterQuantity;
        this.simulated = simulated;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    public static StockPushLog success(ChannelCode channelCode, UUID skuId, String channelProductNo,
                                      Integer beforeQuantity, int afterQuantity, boolean simulated) {
        return StockPushLog.builder()
                .channelCode(channelCode)
                .skuId(skuId)
                .channelProductNo(channelProductNo)
                .beforeQuantity(beforeQuantity)
                .afterQuantity(afterQuantity)
                .simulated(simulated)
                .status(StockPushStatus.SUCCESS)
                .build();
    }

    public static StockPushLog failed(ChannelCode channelCode, UUID skuId, String channelProductNo,
                                     Integer beforeQuantity, int afterQuantity, String errorMessage) {
        return StockPushLog.builder()
                .channelCode(channelCode)
                .skuId(skuId)
                .channelProductNo(channelProductNo)
                .beforeQuantity(beforeQuantity)
                .afterQuantity(afterQuantity)
                .simulated(false)
                .status(StockPushStatus.FAILED)
                .errorMessage(errorMessage)
                .build();
    }

    /** 변동량 상한 초과로 전송을 차단한 경우. */
    public static StockPushLog blocked(ChannelCode channelCode, UUID skuId, String channelProductNo,
                                      Integer beforeQuantity, int afterQuantity, String reason) {
        return StockPushLog.builder()
                .channelCode(channelCode)
                .skuId(skuId)
                .channelProductNo(channelProductNo)
                .beforeQuantity(beforeQuantity)
                .afterQuantity(afterQuantity)
                .simulated(false)
                .status(StockPushStatus.BLOCKED)
                .errorMessage(reason)
                .build();
    }
}
