package kr.suhsaechan.palim.automation.influencer.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelReviewRepository extends JpaRepository<ChannelReview, UUID> {

    Optional<ChannelReview> findByCampaignIdAndChannelId(UUID campaignId, UUID channelId);

    List<ChannelReview> findByCampaignId(UUID campaignId);

    List<ChannelReview> findByCampaignIdAndDecision(UUID campaignId, ReviewDecision decision);
}
