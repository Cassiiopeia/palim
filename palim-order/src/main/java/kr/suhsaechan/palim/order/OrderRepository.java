package kr.suhsaechan.palim.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.suhsaechan.palim.common.ChannelCode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByChannelCodeAndChannelOrderNo(ChannelCode channelCode, String channelOrderNo);

    boolean existsByChannelCodeAndChannelOrderNo(ChannelCode channelCode, String channelOrderNo);

    /** 일일 리포트·엑셀 내보내기용 기간 조회 (F-06, F-07). */
    List<Order> findByOrderedAtBetweenOrderByOrderedAtDesc(Instant from, Instant to);
}
