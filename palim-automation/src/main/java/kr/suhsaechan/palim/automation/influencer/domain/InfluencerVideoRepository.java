package kr.suhsaechan.palim.automation.influencer.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InfluencerVideoRepository extends JpaRepository<InfluencerVideo, UUID> {

    Optional<InfluencerVideo> findByYoutubeVideoId(String youtubeVideoId);

    List<InfluencerVideo> findByYoutubeVideoIdIn(List<String> youtubeVideoIds);

    /** 지표 계산용 — 최근 영상부터. 쇼츠 제외는 계산 엔진이 하므로 여기서는 전부 준다. */
    List<InfluencerVideo> findByChannelIdOrderByPublishedAtDesc(UUID channelId, Limit limit);

    /** 롱폼만 필요한 조회(대표 영상 표시 등). */
    List<InfluencerVideo> findByChannelIdAndShortFormFalseOrderByPublishedAtDesc(
            UUID channelId, Limit limit);
}
