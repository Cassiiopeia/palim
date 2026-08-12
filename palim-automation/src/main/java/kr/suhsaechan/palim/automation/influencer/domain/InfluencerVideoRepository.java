package kr.suhsaechan.palim.automation.influencer.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InfluencerVideoRepository extends JpaRepository<InfluencerVideo, UUID> {

    Optional<InfluencerVideo> findByYoutubeVideoId(String youtubeVideoId);

    List<InfluencerVideo> findByYoutubeVideoIdIn(List<String> youtubeVideoIds);

    /** 지표 계산용 — 최근 영상부터. 쇼츠 제외는 계산 엔진이 하므로 여기서는 전부 준다. */
    List<InfluencerVideo> findByChannelIdOrderByPublishedAtDesc(UUID channelId, Limit limit);

    /** 롱폼만 필요한 조회(대표 영상 표시 등). */
    List<InfluencerVideo> findByChannelIdAndShortFormFalseOrderByPublishedAtDesc(
            UUID channelId, Limit limit);

    /**
     * 기간 내 게시된 영상 — 주간 트렌드 집계가 쓴다.
     *
     * <p>채널을 함께 가져온다. 집계가 영상마다 채널 카테고리를 확인하므로, 지연 로딩이면
     * 영상 수만큼 추가 쿼리가 나간다.
     */
    @EntityGraph(attributePaths = "channel")
    List<InfluencerVideo> findByPublishedAtBetween(Instant from, Instant to);
}
