package kr.suhsaechan.palim.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, UUID> {

    /**
     * 발송 대기 목록.
     *
     * <p>애플리케이션 기동 시와 주기적 relay 에서 이 목록을 읽어 큐로 발행한다. 큐가 유실돼도
     * 이 조회로 복구된다(A-14).
     */
    List<NotificationOutbox> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Limit limit);

    /** 적체 감시용. 임계치를 넘으면 텔레그램으로 경고한다(설계서 9.3). */
    long countByStatus(OutboxStatus status);

    List<NotificationOutbox> findByStatusAndTypeOrderByCreatedAtAsc(OutboxStatus status, NotificationType type);
}
