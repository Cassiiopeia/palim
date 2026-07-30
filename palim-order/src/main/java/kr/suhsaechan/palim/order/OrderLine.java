package kr.suhsaechan.palim.order;

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
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 항목. 재고 차감의 단위다.
 *
 * <p>{@code channelCode}와 {@code channelOrderNo}를 주문에서 중복 저장한다. 유니크 제약을
 * <b>라인 단위</b>로 걸어야 하기 때문이다. 비정규화지만 이 제약이 중복 수집을 막는 유일한
 * 방어선이므로 감수한다(설계서 5.1).
 *
 * <p>{@code skuId}가 nullable 인 이유는 매핑되지 않은 상품의 주문도 저장해야 하기 때문이다.
 * 데이터 유실을 막고, 매핑 완료 후 소급 반영할 수 있어야 한다(F-04).
 */
@Getter
@Entity
@Table(name = "order_line")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderLine extends BaseTimeEntity {

    @Id
    private UUID id;

    /** {@link Order} 식별자. JPA 연관관계 대신 값으로 참조한다. */
    @Column(nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_code", nullable = false, length = 20)
    private ChannelCode channelCode;

    @Column(name = "channel_order_no", nullable = false, length = 100)
    private String channelOrderNo;

    /** 채널이 부여한 주문 항목 식별자. */
    @Column(name = "channel_line_no", nullable = false, length = 100)
    private String channelLineNo;

    @Column(nullable = false, length = 100)
    private String channelProductNo;

    /** 옵션 단위 식별자(쿠팡 vendorItemId 등). 옵션이 없는 상품은 null 이다. */
    @Column(length = 100)
    private String channelOptionNo;

    /** 매핑 화면 표시용. 채널 상품명이 바뀌어도 수집 당시 값을 남긴다. */
    @Column(nullable = false, length = 300)
    private String channelProductName;

    /** 미매핑 주문은 null 로 저장된다 (F-04). */
    private UUID skuId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private long amount;

    /**
     * 재고 반영 여부.
     *
     * <p>미매핑으로 들어온 라인은 false 로 남는다. 매핑 완료 후 이 값을 기준으로 소급 반영
     * 대상을 찾는다(F-04). 재고가 조용히 틀어지는 것을 막는 핵심 필드다.
     */
    @Column(nullable = false)
    private boolean stockApplied;

    @Version
    private Long version;

    @Builder(access = AccessLevel.PRIVATE)
    private OrderLine(UUID orderId, ChannelCode channelCode, String channelOrderNo, String channelLineNo,
                      String channelProductNo, String channelOptionNo, String channelProductName,
                      UUID skuId, int quantity, long unitPrice, long amount) {
        this.id = UuidV7.generate();
        this.orderId = orderId;
        this.channelCode = channelCode;
        this.channelOrderNo = channelOrderNo;
        this.channelLineNo = channelLineNo;
        this.channelProductNo = channelProductNo;
        this.channelOptionNo = channelOptionNo;
        this.channelProductName = channelProductName;
        this.skuId = skuId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.amount = amount;
        this.stockApplied = false;
    }

    public static OrderLine collect(UUID orderId, ChannelCode channelCode, String channelOrderNo,
                                    String channelLineNo, String channelProductNo, String channelOptionNo,
                                    String channelProductName, UUID skuId,
                                    int quantity, long unitPrice, long amount) {
        if (quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_QUANTITY, quantity);
        }
        return OrderLine.builder()
                .orderId(orderId)
                .channelCode(channelCode)
                .channelOrderNo(channelOrderNo)
                .channelLineNo(channelLineNo)
                .channelProductNo(channelProductNo)
                .channelOptionNo(channelOptionNo)
                .channelProductName(channelProductName)
                .skuId(skuId)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .amount(amount)
                .build();
    }

    /** 재고 차감을 완료했음을 기록한다. */
    public void markStockApplied() {
        this.stockApplied = true;
    }

    /** 취소·반품으로 재고를 복원했음을 기록한다. */
    public void markStockRestored() {
        this.stockApplied = false;
    }

    /** 매핑 완료 후 SKU를 연결한다 (F-04 소급 반영). */
    public void assignSku(UUID skuId) {
        if (skuId == null) {
            throw new BusinessException(ErrorCode.ORDER_SKU_ID_REQUIRED);
        }
        this.skuId = skuId;
    }

    public boolean isMapped() {
        return skuId != null;
    }

    /** 매핑은 됐는데 재고가 아직 반영되지 않은 상태 — 소급 반영 대상이다. */
    public boolean awaitsStockApply() {
        return isMapped() && !stockApplied;
    }
}
