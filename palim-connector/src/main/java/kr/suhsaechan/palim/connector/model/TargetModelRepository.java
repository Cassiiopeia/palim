package kr.suhsaechan.palim.connector.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TargetModelRepository extends JpaRepository<TargetModel, UUID> {

    Optional<TargetModel> findByTenantIdAndCode(UUID tenantId, String code);

    List<TargetModel> findByTenantIdOrderByCode(UUID tenantId);

    List<TargetModel> findByTenantIdAndKind(UUID tenantId, TargetModelKind kind);
}
