package kr.suhsaechan.palim.web.sku;

import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.sku.Sku;
import kr.suhsaechan.palim.sku.SkuService;
import kr.suhsaechan.palim.sku.StockMovement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 관리 화면용 조율 서비스.
 *
 * <p>도메인 서비스의 변경 메서드가 {@code MANDATORY} 이므로 트랜잭션을 여는 계층이 필요하다.
 *
 * <h2>재고 조정은 사유를 반드시 받는다</h2>
 *
 * <p>모든 재고 변동은 사유와 함께 이력으로 남아야 추적이 가능하다(F-03). 사유 없이 수량만
 * 바꾸면 나중에 "왜 이 시점에 재고가 줄었는지" 알 수 없고, 정합성 불일치가 발견됐을 때
 * 원인을 찾을 방법이 없다.
 */
@Service
@RequiredArgsConstructor
public class SkuAdminService {

    private static final int SALES_WINDOW_DAYS = 7;

    private final SkuService skuService;

    @Transactional(readOnly = true)
    public List<SkuView> findAll() {
        return skuService.findAllActive().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public SkuView find(UUID skuId) {
        return toView(skuService.getById(skuId));
    }

    @Transactional(readOnly = true)
    public List<StockMovement> findMovements(UUID skuId) {
        return skuService.findMovements(skuId);
    }

    /**
     * SKU 를 등록한다.
     *
     * <p>초기 재고는 도입 시점에 발주자가 실물을 실사해 입력하는 값이다. 서비스가 초기 재고
     * 이력을 함께 남기므로 등록 직후에도 정합성 대조가 성립한다.
     */
    @Transactional
    public void register(String code, String name, int initialQuantity, int safetyThreshold) {
        skuService.register(code.trim(), name.trim(), initialQuantity, safetyThreshold);
    }

    @Transactional
    public void restock(UUID skuId, int quantity, String memo) {
        skuService.restock(skuId, quantity, memo);
    }

    @Transactional
    public void dispose(UUID skuId, int quantity, String memo) {
        skuService.dispose(skuId, quantity, memo);
    }

    /** 실사 조정. 절대값으로 덮어쓰며 이력에는 변경 전후의 차이가 남는다. */
    @Transactional
    public void adjust(UUID skuId, int newQuantity, String memo) {
        skuService.adjust(skuId, newQuantity, memo);
    }

    @Transactional
    public void changeSafetyThreshold(UUID skuId, int threshold) {
        skuService.changeSafetyThreshold(skuId, threshold);
    }

    @Transactional
    public void discontinue(UUID skuId) {
        skuService.discontinue(skuId);
    }

    private SkuView toView(Sku sku) {
        return new SkuView(
                sku.getId(),
                sku.getCode(),
                sku.getName(),
                sku.getQuantity(),
                sku.getSafetyThreshold(),
                sku.isBelowThreshold(),
                sku.isOutOfStock(),
                sku.isOversold(),
                skuService.averageDailySales(sku.getId(), SALES_WINDOW_DAYS),
                skuService.isConsistent(sku.getId()));
    }
}
