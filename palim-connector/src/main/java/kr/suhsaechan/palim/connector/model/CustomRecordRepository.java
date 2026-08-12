package kr.suhsaechan.palim.connector.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomRecordRepository extends JpaRepository<CustomRecord, UUID> {

    /** UPSERT 판정. 유니크 인덱스와 같은 조합으로 찾아야 경합에서 어긋나지 않는다. */
    Optional<CustomRecord> findByTenantIdAndTargetModelIdAndNaturalKey(UUID tenantId,
                                                                      UUID targetModelId,
                                                                      String naturalKey);

    List<CustomRecord> findByRunId(UUID runId);
}
