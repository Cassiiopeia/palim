package kr.suhsaechan.palim.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.common.UuidV7;
import kr.suhsaechan.palim.common.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채널에서 수집한 주문.
 *
 * <p>{@link OrderLine}과 JPA 연관관계를 두지 않는다. 중복 판정과 재고 반영이 <b>라인 단위</b>로
 * 이뤄지므로 애그리게이트 루트를 통한 접근이 오히려 방해가 된다. 라인은 {@code orderId} 값으로
 * 이 주문을 참조한다.
 *
 * <p>테이블명이 {@code orders}인 이유는 {@code order}가 SQL 예약어이기 때문이다.
 */
@Getter
@Entity
@Table(
        name = "orders",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_orders_channel_order_no",
                columnNames = {"channel_code", "channel_order_no"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseTimeEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_code", nullable = false, length = 20)
    private ChannelCode channelCode;

    @Column(name = "channel_order_no", nullable = false, length = 100)
    private String channelOrderNo;

    /** 채널이 알려준 주문 시각. 채널이 KST/UTC를 섞어 주므로 어댑터에서 Instant로 정규화한다. */
    @Column(nullable = false)
    private Instant orderedAt;

    /** 수집 시각. 주문 시각과의 차이가 곧 알림 지연이다. */
    @Column(nullable = false)
    private Instant collectedAt;

    @Column(length = 100)
    private String buyerName;

    /** 원 단위. 원화는 소수점이 없어 long 으로 다룬다. */
    @Column(nullable = false)
    private long totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Version
    private Long version;

    @Builder(access = AccessLevel.PRIVATE)
    private Order(ChannelCode channelCode, String channelOrderNo, Instant orderedAt,
                  Instant collectedAt, String buyerName, long totalAmount) {
        this.id = UuidV7.generate();
        this.channelCode = channelCode;
        this.channelOrderNo = channelOrderNo;
        this.orderedAt = orderedAt;
        this.collectedAt = collectedAt;
        this.buyerName = buyerName;
        this.totalAmount = totalAmount;
        this.status = OrderStatus.PLACED;
    }

    public static Order collect(ChannelCode channelCode, String channelOrderNo, Instant orderedAt,
                                Instant collectedAt, String buyerName, long totalAmount) {
        return Order.builder()
                .channelCode(channelCode)
                .channelOrderNo(channelOrderNo)
                .orderedAt(orderedAt)
                .collectedAt(collectedAt)
                .buyerName(buyerName)
                .totalAmount(totalAmount)
                .build();
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }

    public void markReturned() {
        this.status = OrderStatus.RETURNED;
    }

    /** 재고를 복원해야 하는 상태인지. */
    public boolean requiresStockRestore() {
        return status == OrderStatus.CANCELLED || status == OrderStatus.RETURNED;
    }
}
