package kr.suhsaechan.palim.connector.define;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorMappingRepository extends JpaRepository<ConnectorMapping, UUID> {

    Optional<ConnectorMapping> findByConnectorIdAndStatus(UUID connectorId, MappingStatus status);

    List<ConnectorMapping> findByConnectorIdOrderByVersionDesc(UUID connectorId);

    /** 다음 버전 번호 계산용. 매핑이 하나도 없으면 비어 있고 1 부터 시작한다. */
    Optional<ConnectorMapping> findFirstByConnectorIdOrderByVersionDesc(UUID connectorId);
}
