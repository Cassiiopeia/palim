package kr.suhsaechan.palim.automation.influencer.comment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoCommentRepository extends JpaRepository<VideoComment, UUID> {

    List<VideoComment> findByVideoIdIn(List<UUID> videoIds);

    void deleteByVideoIdIn(List<UUID> videoIds);
}
