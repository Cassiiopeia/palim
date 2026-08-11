package kr.suhsaechan.palim.automation.influencer.transcript;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoTranscriptRepository extends JpaRepository<VideoTranscript, UUID> {

    Optional<VideoTranscript> findByVideoId(UUID videoId);

    List<VideoTranscript> findByVideoIdIn(List<UUID> videoIds);
}
