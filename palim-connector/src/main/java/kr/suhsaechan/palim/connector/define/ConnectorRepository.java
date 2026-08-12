package kr.suhsaechan.palim.connector.define;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorRepository extends JpaRepository<Connector, UUID> {

    Optional<Connector> findByTenantIdAndCode(UUID tenantId, String code);

    List<Connector> findByTenantIdOrderByName(UUID tenantId);

    /** 스케줄러가 도는 대상. cron 이 없는 커넥터는 수동 실행 전용이다. */
    List<Connector> findByEnabledTrueAndScheduleCronIsNotNull();

    boolean existsByTargetModelId(UUID targetModelId);
}
