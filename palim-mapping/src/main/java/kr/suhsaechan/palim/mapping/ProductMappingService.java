package kr.suhsaechan.palim.mapping;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.suhsaechan.palim.common.ChannelCode;
import kr.suhsaechan.palim.common.exception.DuplicateException;
import kr.suhsaechan.palim.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 매핑 도메인 서비스 (F-04).
 *
 * <p>매핑되지 않은 상품의 주문은 재고에 반영되지 않으므로, 매핑은 시스템 동작의 필수 전제다.
 *
 * <p>SKU 를 {@code UUID} 값으로만 다룬다. 이 모듈은 {@code palim-sku} 를 의존하지 않으므로
 * 해당 SKU 가 실제로 존재하는지는 검증하지 않는다. 그 책임은 조율 계층에 있다.
 */
@Service
@RequiredArgsConstructor
public class ProductMappingService {

    private final ProductMappingRepository productMappingRepository;

    /**
     * 채널 상품에 대응하는 SKU 식별자를 찾는다.
     *
     * <p>수집 조율이 재고를 차감할 대상을 결정하는 지점이다. 비어 있으면 미매핑 주문으로
     * 저장하고 알림을 보낸다.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> resolveSkuId(ChannelCode channelCode, String channelProductNo,
                                       String channelOptionNo) {
        return productMappingRepository
                .findActiveBy(channelCode, channelProductNo, channelOptionNo)
                .map(ProductMapping::getSkuId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProductMapping connect(ChannelCode channelCode, String channelProductNo,
                                  String channelOptionNo, String channelProductName, UUID skuId) {
        productMappingRepository.findActiveBy(channelCode, channelProductNo, channelOptionNo)
                .ifPresent(existing -> {
                    throw DuplicateException.of("상품 매핑",
                            "%s / %s".formatted(channelCode, channelProductNo));
                });
        return productMappingRepository.save(ProductMapping.connect(
                channelCode, channelProductNo, channelOptionNo, channelProductName, skuId));
    }

    /** 다른 SKU 로 다시 연결한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconnect(UUID mappingId, UUID skuId) {
        get(mappingId).reconnect(skuId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deactivate(UUID mappingId) {
        get(mappingId).deactivate();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void activate(UUID mappingId) {
        get(mappingId).activate();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshProductName(UUID mappingId, String channelProductName) {
        get(mappingId).refreshProductName(channelProductName);
    }

    @Transactional(readOnly = true)
    public ProductMapping get(UUID mappingId) {
        return productMappingRepository.findById(mappingId)
                .orElseThrow(() -> NotFoundException.of("상품 매핑", mappingId));
    }

    @Transactional(readOnly = true)
    public List<ProductMapping> findByChannel(ChannelCode channelCode) {
        return productMappingRepository.findByChannelCodeOrderByChannelProductNoAsc(channelCode);
    }

    @Transactional(readOnly = true)
    public List<ProductMapping> findBySku(UUID skuId) {
        return productMappingRepository.findBySkuId(skuId);
    }
}
