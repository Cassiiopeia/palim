package kr.suhsaechan.palim.sku;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    List<StockMovement> findBySkuIdOrderByCreatedAtDesc(UUID skuId);

    /**
     * 재고 이력 누적합.
     *
     * <p>{@code Sku.quantity}와 이 값을 대조하는 배치가 일 1회 돈다. 본 시스템은 스스로를
     * "재고의 유일한 기준"으로 정의하므로, 자신의 불일치를 감지하지 못하면 틀어진 상태로
     * 장기간 운영된다. 발주자는 실물 재고 불일치를 발견한 시점에야 인지하게 되고 그때는
     * 원인 추적이 불가능하다(설계서 5.3).
     */
    @Query("select coalesce(sum(m.delta), 0) from StockMovement m where m.skuId = :skuId")
    int sumDeltaBySkuId(@Param("skuId") UUID skuId);

    /** 최근 N일 판매량 — 소진 예상일 계산에 쓴다 (F-05). */
    @Query("""
            select coalesce(sum(-m.delta), 0) from StockMovement m
            where m.skuId = :skuId and m.reason = kr.suhsaechan.palim.sku.StockChangeReason.SALE
              and m.createdAt >= :from
            """)
    int sumSoldQuantitySince(@Param("skuId") UUID skuId, @Param("from") Instant from);
}
