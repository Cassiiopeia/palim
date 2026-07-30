package kr.suhsaechan.palim.web.mapping;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.suhsaechan.palim.collector.OrderIngestionService;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.mapping.ProductMapping;
import kr.suhsaechan.palim.mapping.ProductMappingService;
import kr.suhsaechan.palim.order.OrderLine;
import kr.suhsaechan.palim.order.OrderService;
import kr.suhsaechan.palim.sku.Sku;
import kr.suhsaechan.palim.sku.SkuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 매핑 화면용 조율 서비스 (F-04).
 *
 * <h2>매핑 등록 직후 소급 반영을 수행한다</h2>
 *
 * <p>미매핑으로 저장된 주문은 재고에 반영되지 않은 상태다. 매핑을 등록해도 소급 반영이 없으면
 * <b>재고가 계속 틀어진 채 남는다.</b> 주기 배치({@code UnmappedOrderReconciler})가 결국
 * 정리하지만, 발주자는 매핑 직후에 결과를 확인하고 싶어한다.
 *
 * <p>소급 반영은 항목 1건 단위 트랜잭션이므로 하나가 실패해도 나머지는 처리된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MappingAdminService {

    private final ProductMappingService productMappingService;
    private final OrderService orderService;
    private final SkuService skuService;
    private final OrderIngestionService orderIngestionService;

    /**
     * 채널별 매핑 목록.
     *
     * <p>SKU 조회를 매핑 건수만큼 반복하지 않도록 한 번에 읽어 맵으로 합친다. 매핑이 수백 건
     * 쌓이면 N+1 이 화면 응답에 그대로 드러난다.
     */
    @Transactional(readOnly = true)
    public List<MappingView> findByChannel(ChannelCode channelCode) {
        List<ProductMapping> mappings = productMappingService.findByChannel(channelCode);

        Map<UUID, Sku> skuById = skuService.findAllByIds(
                        mappings.stream().map(ProductMapping::getSkuId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Sku::getId, sku -> sku));

        return mappings.stream()
                .map(mapping -> {
                    Sku sku = skuById.get(mapping.getSkuId());
                    return new MappingView(
                            mapping.getId(),
                            mapping.getChannelProductNo(),
                            mapping.getChannelOptionNo(),
                            mapping.getChannelProductName(),
                            mapping.getSkuId(),
                            sku != null ? sku.getCode() : "(삭제됨)",
                            sku != null ? sku.getName() : "-",
                            mapping.isActive());
                })
                .toList();
    }

    /** 등록된 SKU 목록. 매핑 대상 선택에 쓴다. */
    @Transactional(readOnly = true)
    public List<Sku> findSelectableSkus() {
        return skuService.findAllActive();
    }

    /**
     * 미매핑 주문 항목.
     *
     * <p>여기서 바로 SKU 를 연결할 수 있어야 한다. 발주자가 채널 상품코드를 따로 찾아 입력하게
     * 만들면 오타가 나고, 오타 난 매핑은 영원히 매칭되지 않는다.
     */
    @Transactional(readOnly = true)
    public List<UnmappedLineView> findUnmappedLines() {
        return orderService.findUnmappedLines().stream()
                .map(line -> new UnmappedLineView(
                        line.getId(),
                        line.getChannelCode(),
                        line.getChannelCode().displayName(),
                        line.getChannelOrderNo(),
                        line.getChannelProductNo(),
                        line.getChannelOptionNo(),
                        line.getChannelProductName(),
                        line.getQuantity(),
                        line.getCreatedAt()))
                .toList();
    }

    /**
     * 매핑을 등록하고 재고를 소급 반영한다.
     *
     * @return 소급 반영된 주문 항목 수
     */
    @Transactional
    public int connect(ChannelCode channelCode, String channelProductNo, String channelOptionNo,
                       String channelProductName, UUID skuId) {
        skuService.getById(skuId);   // 존재 검증은 조율 계층 책임 (palim-mapping 은 palim-sku 를 모른다)

        productMappingService.connect(channelCode, channelProductNo,
                blankToNull(channelOptionNo), channelProductName, skuId);

        return reconcile(channelCode, channelProductNo, channelOptionNo);
    }

    @Transactional
    public void reconnect(UUID mappingId, UUID skuId) {
        skuService.getById(skuId);
        productMappingService.reconnect(mappingId, skuId);
    }

    @Transactional
    public void deactivate(UUID mappingId) {
        productMappingService.deactivate(mappingId);
    }

    @Transactional
    public void activate(UUID mappingId) {
        productMappingService.activate(mappingId);
    }

    /**
     * 해당 채널 상품의 미매핑 주문에 재고를 소급 반영한다.
     *
     * <p>항목별로 별도 트랜잭션이 열리므로 하나가 실패해도 나머지는 처리된다. 실패한 항목은
     * 주기 배치가 다음에 다시 시도한다.
     */
    private int reconcile(ChannelCode channelCode, String channelProductNo, String channelOptionNo) {
        List<OrderLine> targets = orderService.findUnmappedLinesFor(
                channelCode, channelProductNo, blankToNull(channelOptionNo));

        int applied = 0;
        for (OrderLine line : targets) {
            try {
                if (orderIngestionService.applyStockRetroactively(line.getId())) {
                    applied++;
                }
            } catch (RuntimeException exception) {
                log.error("소급 반영 실패 — 주문항목 {}", line.getId(), exception);
            }
        }
        return applied;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
