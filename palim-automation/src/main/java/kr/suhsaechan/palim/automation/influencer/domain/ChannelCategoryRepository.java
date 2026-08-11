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
}
