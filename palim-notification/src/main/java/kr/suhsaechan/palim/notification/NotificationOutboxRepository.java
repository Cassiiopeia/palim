package kr.suhsaechan.palim.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * 같은 억제 키로 지정 시각 이후에 등록된 알림이 있는지.
     *
     * <p>감시 배치가 재알림 주기를 지키는 근거다. 발송 성공 여부와 무관하게 <b>등록 시각</b>을
     * 기준으로 판단한다 — 발송이 실패해 재시도 중인 알림이 있는데 또 등록하면 중복이 쌓인다.
     */
    boolean existsByDedupeKeyAndCreatedAtAfter(String dedupeKey, Instant after);

    /** 이력 화면용 (#32). 정렬은 서비스가 지정한다. */
    Page<NotificationOutbox> findByStatus(OutboxStatus status, Pageable pageable);
}
