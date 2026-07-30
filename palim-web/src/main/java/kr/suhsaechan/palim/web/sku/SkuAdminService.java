package kr.suhsaechan.palim.web.sku;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.suhsaechan.palim.audit.AuditType;
import kr.suhsaechan.palim.sku.Sku;
import kr.suhsaechan.palim.sku.SkuService;
import kr.suhsaechan.palim.sku.StockMovement;
import kr.suhsaechan.palim.web.audit.WebAuditRecorder;
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
 *
 * <h2>감사 로그와 재고 이력은 역할이 다르다</h2>
 *
 * <p>{@code StockMovement} 는 수량의 변화를, 감사 로그는 <b>누가 어디서</b> 했는지를 남긴다.
 * 감사 기록은 별도 트랜잭션이라 본 작업이 롤백돼도 남는다 — 시도 자체가 감사 대상이다.
 */
@Service
@RequiredArgsConstructor
public class SkuAdminService {

    private static final int SALES_WINDOW_DAYS = 7;
    private static final String TARGET_TYPE = "SKU";

    private final SkuService skuService;
    private final WebAuditRecorder webAuditRecorder;

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
        Sku sku = skuService.register(code.trim(), name.trim(), initialQuantity, safetyThreshold);

        webAuditRecorder.recordChange(AuditType.SKU_CREATE, TARGET_TYPE, sku.getCode(),
                "SKU %s 을(를) 등록했습니다.".formatted(sku.getCode()),
                null,
                Map.of("code", sku.getCode(), "name", sku.getName(),
                        "quantity", initialQuantity, "safetyThreshold", safetyThreshold));
    }

    @Transactional
    public void restock(UUID skuId, int quantity, String memo) {
        Sku sku = skuService.getById(skuId);
        int before = sku.getQuantity();
        skuService.restock(skuId, quantity, memo);

        recordStockChange(sku, "입고", before, quantity, memo);
    }

    @Transactional
    public void dispose(UUID skuId, int quantity, String memo) {
        Sku sku = skuService.getById(skuId);
        int before = sku.getQuantity();
        skuService.dispose(skuId, quantity, memo);

        recordStockChange(sku, "폐기·분실", before, -quantity, memo);
    }

    /** 실사 조정. 절대값으로 덮어쓰며 이력에는 변경 전후의 차이가 남는다. */
    @Transactional
    public void adjust(UUID skuId, int newQuantity, String memo) {
        Sku sku = skuService.getById(skuId);
        int before = sku.getQuantity();
        skuService.adjust(skuId, newQuantity, memo);

        recordStockChange(sku, "실사 조정", before, newQuantity - before, memo);
    }

    @Transactional
    public void changeSafetyThreshold(UUID skuId, int threshold) {
        Sku sku = skuService.getById(skuId);
        int before = sku.getSafetyThreshold();
        skuService.changeSafetyThreshold(skuId, threshold);

        webAuditRecorder.recordChange(AuditType.SKU_UPDATE, TARGET_TYPE, sku.getCode(),
                "SKU %s 안전재고 임계치를 변경했습니다.".formatted(sku.getCode()),
                Map.of("safetyThreshold", before),
                Map.of("safetyThreshold", threshold));
    }

    @Transactional
    public void discontinue(UUID skuId) {
        Sku sku = skuService.getById(skuId);
        skuService.discontinue(skuId);

        webAuditRecorder.recordChange(AuditType.SKU_DISCONTINUE, TARGET_TYPE, sku.getCode(),
                "SKU %s 을(를) 단종 처리했습니다.".formatted(sku.getCode()), null, null);
    }

    private void recordStockChange(Sku sku, String action, int before, int delta, String memo) {
        webAuditRecorder.recordChange(AuditType.STOCK_ADJUST, TARGET_TYPE, sku.getCode(),
                "SKU %s %s — %d → %d".formatted(sku.getCode(), action, before, before + delta),
                Map.of("quantity", before),
                memo == null || memo.isBlank()
                        ? Map.of("quantity", before + delta)
                        : Map.of("quantity", before + delta, "memo", memo));
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
