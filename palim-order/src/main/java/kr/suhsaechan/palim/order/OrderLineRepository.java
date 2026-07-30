package kr.suhsaechan.palim.order;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.ChannelCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderLineRepository extends JpaRepository<OrderLine, UUID> {

    List<OrderLine> findByOrderId(UUID orderId);

    /**
     * 중복 존재 확인.
     *
     * <p>주의 — 이 메서드로 "없으면 삽입"을 판단하면 수집이 중첩되는 순간 뚫린다. 중복 방지의
     * 근거는 유니크 제약이며, 삽입 성공 여부를 판정 기준으로 삼아야 한다(설계서 5.1).
     * 이 메서드는 화면 조회나 진단 목적으로만 쓴다.
     */
    boolean existsByChannelCodeAndChannelOrderNoAndChannelLineNo(
            ChannelCode channelCode, String channelOrderNo, String channelLineNo);

    /** 미매핑 주문 항목 — 매핑 필요 알림 대상 (F-04). */
    List<OrderLine> findBySkuIdIsNullOrderByCreatedAtDesc();

    /**
     * 매핑 완료 후 재고를 소급 반영할 대상.
     *
     * <p>옵션 식별자가 null 일 수 있어 {@code is null} 비교를 함께 처리한다.
     */
    @Query("""
            select l from OrderLine l
            where l.channelCode = :channelCode
              and l.channelProductNo = :channelProductNo
              and (:channelOptionNo is null or l.channelOptionNo = :channelOptionNo)
              and l.skuId is null
            order by l.createdAt asc
            """)
    List<OrderLine> findUnmappedFor(@Param("channelCode") ChannelCode channelCode,
                                    @Param("channelProductNo") String channelProductNo,
                                    @Param("channelOptionNo") String channelOptionNo);

    /** 매핑은 됐으나 재고가 반영되지 않은 항목 — 소급 반영 실행 대상. */
    List<OrderLine> findBySkuIdIsNotNullAndStockAppliedFalseOrderByCreatedAtAsc();
}
