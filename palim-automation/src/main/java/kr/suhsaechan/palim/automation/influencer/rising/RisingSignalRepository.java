package kr.suhsaechan.palim.automation.influencer.rising;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

public interface RisingSignalRepository extends JpaRepository<RisingSignal, UUID> {

    Optional<RisingSignal> findByChannelId(UUID channelId);

    /** 레이더 화면 — 활성 신호를 지수순으로. */
    List<RisingSignal> findByActiveTrueOrderByTotalDesc(Limit limit);

    /** 주간 알림 — 이번 주 새로 감지된 것만. 이미 알린 채널을 반복해서 보내지 않는다. */
    List<RisingSignal> findByActiveTrueAndDetectedAtAfterOrderByTotalDesc(
            @Param("detectedAt") Instant detectedAt, Limit limit);

    long countByActiveTrue();
}
