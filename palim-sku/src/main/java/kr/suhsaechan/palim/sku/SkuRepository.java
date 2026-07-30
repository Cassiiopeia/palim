package kr.suhsaechan.palim.sku;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkuRepository extends JpaRepository<Sku, UUID> {

    Optional<Sku> findByCode(String code);

    boolean existsByCode(String code);

    List<Sku> findAllByActiveTrueOrderByCodeAsc();

    /**
     * 재고 차감을 위해 비관적 락으로 조회한다.
     *
     * <p>낙관적 락과 재시도 조합도 가능하나 재시도 로직의 결함이 곧 이중 차감으로 이어진다.
     * 일 주문 수십 건 규모에서는 경합이 사실상 없으므로 락 비용이 무시할 수준이고, 처리
     * 흐름이 단순해진다(설계서 5.2).
     *
     * <p>락 구간 안에서 채널 API 호출이나 텔레그램 발송을 수행하지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sku s where s.id = :id")
    Optional<Sku> findForUpdateById(@Param("id") UUID id);

    /** 안전재고 미달 목록 (F-05). */
    @Query("select s from Sku s where s.active = true and s.quantity < s.safetyThreshold order by s.quantity asc")
    List<Sku> findBelowThreshold();
}
