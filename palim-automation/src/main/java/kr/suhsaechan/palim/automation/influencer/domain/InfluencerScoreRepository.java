package kr.suhsaechan.palim.automation.influencer.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InfluencerScoreRepository extends JpaRepository<InfluencerScore, UUID> {

    Optional<InfluencerScore> findByCampaignIdAndChannelId(UUID campaignId, UUID channelId);

    /** 등급표 기본 정렬 — 총점순. 하드 탈락 건은 목록에서 뺀다. */
    List<InfluencerScore> findByCampaignIdAndHardFailReasonIsNullOrderByTotalDesc(
            UUID campaignId, Limit limit);

    /** 사장님이 실제로 보는 정렬 — 단가 대비 도달 효율순. */
    List<InfluencerScore> findByCampaignIdAndHardFailReasonIsNullOrderByEstimatedCpvAsc(
            UUID campaignId, Limit limit);
}
