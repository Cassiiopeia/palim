package kr.suhsaechan.palim.mapping;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.suhsaechan.palim.common.ChannelCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductMappingRepository extends JpaRepository<ProductMapping, UUID> {

    /**
     * 채널 상품코드로 매핑을 조회한다.
     *
     * <p>옵션 식별자가 null 일 수 있어 {@code is null} 비교를 함께 처리한다. 파생 쿼리 메서드로는
     * null 파라미터를 {@code = null}로 번역해 항상 결과가 없으므로 명시적 JPQL을 쓴다.
     */
    @Query("""
            select m from ProductMapping m
            where m.channelCode = :channelCode
              and m.channelProductNo = :channelProductNo
              and ((:channelOptionNo is null and m.channelOptionNo is null)
                   or m.channelOptionNo = :channelOptionNo)
              and m.active = true
            """)
    Optional<ProductMapping> findActiveBy(@Param("channelCode") ChannelCode channelCode,
                                          @Param("channelProductNo") String channelProductNo,
                                          @Param("channelOptionNo") String channelOptionNo);

    List<ProductMapping> findByChannelCodeOrderByChannelProductNoAsc(ChannelCode channelCode);

    List<ProductMapping> findBySkuId(UUID skuId);
}
