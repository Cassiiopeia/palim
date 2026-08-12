package kr.suhsaechan.palim.automation.influencer.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelCategoryRepository extends JpaRepository<ChannelCategory, UUID> {

    List<ChannelCategory> findByChannelId(UUID channelId);

    Optional<ChannelCategory> findByChannelIdAndTaxonomyAndCategoryCode(
            UUID channelId, CategoryTaxonomy taxonomy, String categoryCode);

    List<ChannelCategory> findByTaxonomyAndCategoryCode(CategoryTaxonomy taxonomy, String categoryCode);

    /**
     * 여러 채널의 라벨을 한 번에 — 트렌드 집계가 쓴다.
     *
     * <p>채널을 함께 가져온다. 집계가 라벨에서 채널 ID 로 되짚으므로 지연 로딩이면 라벨 수만큼
     * 추가 쿼리가 나간다.
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "channel")
    List<ChannelCategory> findByChannelIdIn(java.util.Collection<UUID> channelIds);
}
