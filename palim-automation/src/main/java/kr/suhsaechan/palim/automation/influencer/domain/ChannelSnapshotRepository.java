package kr.suhsaechan.palim.automation.influencer.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelSnapshotRepository extends JpaRepository<ChannelSnapshot, UUID> {

    boolean existsByChannelIdAndCapturedOn(UUID channelId, LocalDate capturedOn);

    List<ChannelSnapshot> findByChannelIdOrderByCapturedOnAsc(UUID channelId);
}
