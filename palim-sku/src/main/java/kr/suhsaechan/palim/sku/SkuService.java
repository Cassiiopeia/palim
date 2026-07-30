package kr.suhsaechan.palim.sku;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SKU · 재고 도메인 서비스.
 *
 * <h2>재고 변경과 이력 기록은 항상 짝으로 일어난다</h2>
 *
 * <p>{@code Sku.quantity} 변경과 {@link StockMovement} 삽입이 분리되면 대조 배치(설계서 5.3)가
 * 즉시 불일치를 보고한다. 호출자가 둘을 각각 호출하는 구조로 두면 언젠가 한쪽을 빠뜨리므로,
 * 이 서비스의 변경 메서드가 두 작업을 함께 수행한다. <b>호출자는 StockMovement 를 직접 만들지
 * 않는다.</b>
 *
 * <h2>트랜잭션은 호출자가 연다</h2>
 *
 * <p>변경 메서드는 {@link Propagation#MANDATORY} 다. 설계서 3.4가 정한 "도메인 서비스는 자체
 * 트랜잭션을 열지 않는다"를 런타임에 강제하기 위함이다. 단순히 애너테이션을 생략하면 호출자가
 * 트랜잭션을 잊었을 때 재고 변경과 이력 기록이 각각 커밋되어, 중간 실패 시 정합성이 깨진 채
 * 조용히 넘어간다. MANDATORY 는 그 실수를 즉시 예외로 드러낸다.
 */
@Service
@RequiredArgsConstructor
public class SkuService {

    private final SkuRepository skuRepository;
    private final StockMovementRepository stockMovementRepository;

    // ------------------------------------------------------------------
    // 등록 · 수정
    // ------------------------------------------------------------------

    /**
     * SKU 를 등록한다.
     *
     * <p>초기 재고에 대응하는 이력을 함께 남긴다. 이 이력이 없으면 대조 배치의
     * {@code SUM(delta) == quantity} 가 항상 초기 수량만큼 어긋나 정상 상태를 불일치로 오판한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Sku register(String code, String name, int initialQuantity, int safetyThreshold) {
        if (skuRepository.existsByCode(code)) {
            throw new BusinessException(ErrorCode.SKU_CODE_DUPLICATE, code);
        }
        Sku sku = skuRepository.save(Sku.register(code, name, initialQuantity, safetyThreshold));
        stockMovementRepository.save(StockMovement.ofInitialStock(sku.getId(), initialQuantity));
        return sku;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void rename(UUID skuId, String name) {
        lock(skuId).rename(name);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changeSafetyThreshold(UUID skuId, int threshold) {
        lock(skuId).changeSafetyThreshold(threshold);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void discontinue(UUID skuId) {
        lock(skuId).discontinue();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resume(UUID skuId) {
        lock(skuId).resume();
    }

    // ------------------------------------------------------------------
    // 재고 변동 — 이력이 함께 기록된다
    // ------------------------------------------------------------------

    /**
     * 판매에 따른 차감. 오버셀링 시 음수 재고를 허용한다.
     *
     * @return 이 차감으로 재고가 음수가 되었는지 여부. true 면 오버셀링 알림 대상이다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean decreaseForSale(UUID skuId, int quantity, UUID orderLineId) {
        Sku sku = lock(skuId);
        boolean oversold = sku.decreaseForSale(quantity);
        stockMovementRepository.save(
                StockMovement.ofSale(skuId, quantity, sku.getQuantity(), orderLineId));
        return oversold;
    }

    /** 취소·반품에 따른 복원. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void increaseForCancel(UUID skuId, int quantity, UUID orderLineId) {
        Sku sku = lock(skuId);
        sku.increase(quantity);
        stockMovementRepository.save(
                StockMovement.ofCancelRestore(skuId, quantity, sku.getQuantity(), orderLineId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void restock(UUID skuId, int quantity, String memo) {
        Sku sku = lock(skuId);
        sku.increase(quantity);
        stockMovementRepository.save(
                StockMovement.ofRestock(skuId, quantity, sku.getQuantity(), memo));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void dispose(UUID skuId, int quantity, String memo) {
        Sku sku = lock(skuId);
        sku.decrease(quantity);
        stockMovementRepository.save(
                StockMovement.ofDisposal(skuId, quantity, sku.getQuantity(), memo));
    }

    /**
     * 실사 조정. 절대값으로 덮어쓴다.
     *
     * <p>이력의 {@code delta} 는 변경 전후의 차이로 기록된다. 그래야 누적합 대조가 성립한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void adjust(UUID skuId, int newQuantity, String memo) {
        Sku sku = lock(skuId);
        int before = sku.getQuantity();
        sku.adjustTo(newQuantity);
        stockMovementRepository.save(
                StockMovement.ofAdjustment(skuId, before, sku.getQuantity(), memo));
    }

    // ------------------------------------------------------------------
    // 조회
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Sku getById(UUID skuId) {
        return skuRepository.findById(skuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SKU_NOT_FOUND, skuId));
    }

    @Transactional(readOnly = true)
    public Sku getByCode(String code) {
        return skuRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.SKU_NOT_FOUND, code));
    }

    @Transactional(readOnly = true)
    public List<Sku> findAllActive() {
        return skuRepository.findAllByActiveTrueOrderByCodeAsc();
    }

    /** 안전재고 미달 목록 (F-05). */
    @Transactional(readOnly = true)
    public List<Sku> findBelowThreshold() {
        return skuRepository.findBelowThreshold();
    }

    @Transactional(readOnly = true)
    public List<StockMovement> findMovements(UUID skuId) {
        return stockMovementRepository.findBySkuIdOrderByCreatedAtDesc(skuId);
    }

    /**
     * 재고 스냅샷과 이력 누적합이 일치하는지 검사한다 (설계서 5.3).
     *
     * <p>일 1회 대조 배치가 이 메서드를 호출한다. 불일치가 나오면 텔레그램으로 경고한다.
     */
    @Transactional(readOnly = true)
    public boolean isConsistent(UUID skuId) {
        Sku sku = getById(skuId);
        return stockMovementRepository.sumDeltaBySkuId(skuId) == sku.getQuantity();
    }

    /**
     * 이력 누적합.
     *
     * <p>정합성 대조 배치가 불일치를 보고할 때 실제 값을 함께 담기 위해 쓴다.
     * 단순 일치 여부만 필요하면 {@link #isConsistent} 를 쓴다.
     */
    @Transactional(readOnly = true)
    public int sumMovementDelta(UUID skuId) {
        return stockMovementRepository.sumDeltaBySkuId(skuId);
    }

    /** 최근 N일 평균 판매량. 소진 예상일 계산에 쓴다 (F-05). */
    @Transactional(readOnly = true)
    public double averageDailySales(UUID skuId, int days) {
        if (days <= 0) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_AMOUNT, days);
        }
        Instant from = Instant.now().minus(days, ChronoUnit.DAYS);
        return (double) stockMovementRepository.sumSoldQuantitySince(skuId, from) / days;
    }

    // ------------------------------------------------------------------

    /**
     * 비관적 락으로 조회한다.
     *
     * <p>낙관적 락과 재시도 조합도 가능하나 재시도 로직의 결함이 곧 이중 차감으로 이어진다.
     * 일 주문 수십 건 규모에서는 경합이 사실상 없어 락 비용이 무시할 수준이다(설계서 5.2).
     */
    private Sku lock(UUID skuId) {
        return skuRepository.findForUpdateById(skuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SKU_NOT_FOUND, skuId));
    }
}
