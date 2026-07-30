package kr.suhsaechan.palim.channel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.suhsaechan.palim.common.ChannelCode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockPushLogRepository extends JpaRepository<StockPushLog, UUID> {

    List<StockPushLog> findBySkuIdOrderByCreatedAtDesc(UUID skuId);

    List<StockPushLog> findByChannelCodeAndCreatedAtAfterOrderByCreatedAtDesc(
            ChannelCode channelCode, Instant after);

    /** 전송 실패·차단 이력 — 시스템 로그 화면과 경고 대상. */
    List<StockPushLog> findByStatusInOrderByCreatedAtDesc(List<StockPushStatus> statuses);
}
