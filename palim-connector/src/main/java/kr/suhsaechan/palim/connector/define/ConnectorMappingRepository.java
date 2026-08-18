package kr.suhsaechan.palim.connector.define;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorMappingRepository extends JpaRepository<ConnectorMapping, UUID> {

    Optional<ConnectorMapping> findByConnectorIdAndStatus(UUID connectorId, MappingStatus status);

    /** 길마다 확정판이 하나씩이다. 자동 수집용을 확정한다고 파일용이 밀려나면 안 된다. */
    Optional<ConnectorMapping> findByConnectorIdAndIntakeAndStatus(
            UUID connectorId, Intake intake, MappingStatus status);

    List<ConnectorMapping> findByConnectorIdAndIntakeOrderByVersionDesc(
            UUID connectorId, Intake intake);

    List<ConnectorMapping> findByConnectorIdOrderByVersionDesc(UUID connectorId);

    /** 다음 버전 번호 계산용. 매핑이 하나도 없으면 비어 있고 1 부터 시작한다. */
    Optional<ConnectorMapping> findFirstByConnectorIdOrderByVersionDesc(UUID connectorId);
}
