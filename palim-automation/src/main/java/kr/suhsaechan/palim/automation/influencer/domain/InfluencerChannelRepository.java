package kr.suhsaechan.palim.automation.influencer.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InfluencerChannelRepository extends JpaRepository<InfluencerChannel, UUID> {

    Optional<InfluencerChannel> findByYoutubeChannelId(String youtubeChannelId);

    boolean existsByYoutubeChannelId(String youtubeChannelId);

    List<InfluencerChannel> findByYoutubeChannelIdIn(List<String> youtubeChannelIds);

    /**
     * 갱신 대상 조회 — 해당 티어에서 기한이 지난 채널을 오래된 순으로.
     *
     * <p>{@code lastRefreshedAt} 이 null 인 신규 채널이 먼저 나와야 하므로 nulls first 를
     * 명시한다(PostgreSQL 의 ASC 기본값은 nulls last 다).
     */
    @org.springframework.data.jpa.repository.Query("""
            select c from InfluencerChannel c
            where c.status = kr.suhsaechan.palim.automation.influencer.domain.ChannelStatus.ACTIVE
              and c.refreshTier = :tier
              and (c.lastRefreshedAt is null or c.lastRefreshedAt < :threshold)
            order by c.lastRefreshedAt asc nulls first
            """)
    List<InfluencerChannel> findRefreshTargets(
            @org.springframework.data.repository.query.Param("tier") RefreshTier tier,
            @org.springframework.data.repository.query.Param("threshold") Instant threshold,
            Limit limit);
}
