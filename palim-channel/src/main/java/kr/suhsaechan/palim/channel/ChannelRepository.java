package kr.suhsaechan.palim.channel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.suhsaechan.palim.common.ChannelCode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelRepository extends JpaRepository<Channel, UUID> {

    Optional<Channel> findByCode(ChannelCode code);

    List<Channel> findAllByEnabledTrue();

    /** 연속 실패가 발생한 채널 — 경고 발송 대상 (A-10). */
    List<Channel> findByConsecutiveFailureCountGreaterThan(int threshold);
}
