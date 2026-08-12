package kr.suhsaechan.palim.connector.run;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorStagingRepository extends JpaRepository<ConnectorStaging, UUID> {

    List<ConnectorStaging> findByRunIdOrderByRowNumber(UUID runId);

    /** 같은 실행을 다시 돌릴 때 이전 결과를 먼저 지운다. 남겨두면 미리보기가 두 배로 보인다. */
    void deleteByTenantIdAndRunId(UUID tenantId, UUID runId);
}
