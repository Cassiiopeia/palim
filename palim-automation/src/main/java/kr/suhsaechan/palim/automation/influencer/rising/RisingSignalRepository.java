package kr.suhsaechan.palim.automation.influencer.rising;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

public interface RisingSignalRepository extends JpaRepository<RisingSignal, UUID> {

    Optional<RisingSignal> findByChannelId(UUID channelId);

    /**
     * 레이더 화면 — 활성 신호를 지수순으로.
     *
     * <p>채널을 함께 가져온다. 신호만 읽고 화면에서 {@code getChannel()} 을 부르면 트랜잭션이
     * 이미 끝나 있어 지연 로딩이 실패한다 — 조회 결과를 화면까지 들고 가는 구조에서는
     * 필요한 연관을 조회 시점에 확정해야 한다.
     */
    @EntityGraph(attributePaths = "channel")
    List<RisingSignal> findByActiveTrueOrderByTotalDesc(Limit limit);

    /** 주간 알림 — 이번 주 새로 감지된 것만. 이미 알린 채널을 반복해서 보내지 않는다. */
    @EntityGraph(attributePaths = "channel")
    List<RisingSignal> findByActiveTrueAndDetectedAtAfterOrderByTotalDesc(
            @Param("detectedAt") Instant detectedAt, Limit limit);

    long countByActiveTrue();
}
