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

    /**
     * 비활성 매핑까지 포함해 조회한다.
     *
     * <p>유니크 인덱스 {@code uk_product_mapping_channel_product} 에는 {@code active} 가 없다.
     * 즉 <b>해제된 매핑도 행으로 남아 같은 채널 상품의 재등록을 막는다.</b> {@link #findActiveBy}
     * 만으로 중복을 판단하면 해제 후 재등록 시 DB 유니크 위반이 터진다.
     */
    @Query("""
            select m from ProductMapping m
            where m.channelCode = :channelCode
              and m.channelProductNo = :channelProductNo
              and ((:channelOptionNo is null and m.channelOptionNo is null)
                   or m.channelOptionNo = :channelOptionNo)
            """)
    Optional<ProductMapping> findAnyBy(@Param("channelCode") ChannelCode channelCode,
                                       @Param("channelProductNo") String channelProductNo,
                                       @Param("channelOptionNo") String channelOptionNo);

    List<ProductMapping> findByChannelCodeOrderByChannelProductNoAsc(ChannelCode channelCode);

    List<ProductMapping> findBySkuId(UUID skuId);
}
