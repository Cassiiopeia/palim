package kr.suhsaechan.palim.mapping;

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
 * 채널 상품코드와 자사 SKU의 연결 (F-04).
 *
 * <p>매핑되지 않은 상품의 주문은 재고에 반영되지 않으므로, 매핑은 시스템 동작의 필수 전제다.
 *
 * <p>채널 상품 목록을 별도 테이블로 캐시하지 않는다. 매핑 화면은 채널 어댑터로 실시간 조회한
 * 목록과 이 테이블을 대조해 미매핑 항목을 표시한다. 캐시를 두면 채널 상품 변경과의 동기화
 * 문제가 추가로 생긴다.
 *
 * <p>{@code skuId}는 값 참조다. 이 모듈은 {@code palim-sku}·{@code palim-channel}을 의존하지 않는다.
 */
@Getter
@Entity
@Table(name = "product_mapping")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductMapping extends BaseTimeEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_code", nullable = false, length = 20)
    private ChannelCode channelCode;

    @Column(name = "channel_product_no", nullable = false, length = 100)
    private String channelProductNo;

    /** 옵션 단위 식별자. 옵션이 없는 상품은 null 이다. */
    @Column(name = "channel_option_no", length = 100)
    private String channelOptionNo;

    /** 매핑 시점의 채널 상품명. 화면 확인용이며 채널에서 바뀌어도 자동 갱신하지 않는다. */
    @Column(nullable = false, length = 300)
    private String channelProductName;

    @Column(nullable = false)
    private UUID skuId;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private Long version;

    @Builder(access = AccessLevel.PRIVATE)
    private ProductMapping(ChannelCode channelCode, String channelProductNo, String channelOptionNo,
                           String channelProductName, UUID skuId) {
        this.id = UuidV7.generate();
        this.channelCode = channelCode;
        this.channelProductNo = channelProductNo;
        this.channelOptionNo = channelOptionNo;
        this.channelProductName = channelProductName;
        this.skuId = skuId;
    }

    public static ProductMapping connect(ChannelCode channelCode, String channelProductNo,
                                         String channelOptionNo, String channelProductName, UUID skuId) {
        if (skuId == null) {
            throw new IllegalArgumentException("연결할 SKU 식별자가 없습니다");
        }
        return ProductMapping.builder()
                .channelCode(channelCode)
                .channelProductNo(channelProductNo)
                .channelOptionNo(channelOptionNo)
                .channelProductName(channelProductName)
                .skuId(skuId)
                .build();
    }

    /** 다른 SKU로 다시 연결한다. */
    public void reconnect(UUID skuId) {
        if (skuId == null) {
            throw new IllegalArgumentException("연결할 SKU 식별자가 없습니다");
        }
        this.skuId = skuId;
    }

    public void refreshProductName(String channelProductName) {
        this.channelProductName = channelProductName;
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }
}
