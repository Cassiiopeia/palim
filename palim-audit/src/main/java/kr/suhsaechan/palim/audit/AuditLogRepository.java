package kr.suhsaechan.palim.audit;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository
        extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {

    /**
     * 보존기간이 지난 기록을 지운다.
     *
     * <p>엔티티를 읽어 하나씩 삭제하면 수십만 건에서 힙이 터진다. 벌크 DELETE 로 처리한다.
     * 영속성 컨텍스트를 우회하므로 이 메서드를 다른 조회와 같은 트랜잭션에서 섞어 쓰지 않는다.
     *
     * @return 삭제된 행 수
     */
    @Modifying
    @Query("delete from AuditLog a where a.occurredAt < :cutoff")
    int deleteByOccurredAtBefore(@Param("cutoff") Instant cutoff);

    /** 정리 대상 건수. 삭제 전에 로그로 남길 값이다. */
    @Query("select count(a) from AuditLog a where a.occurredAt < :cutoff")
    long countByOccurredAtBefore(@Param("cutoff") Instant cutoff);
}
