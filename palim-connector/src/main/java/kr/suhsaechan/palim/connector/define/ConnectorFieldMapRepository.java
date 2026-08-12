package kr.suhsaechan.palim.connector.define;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorFieldMapRepository extends JpaRepository<ConnectorFieldMap, UUID> {

    List<ConnectorFieldMap> findByMappingIdOrderBySortOrder(UUID mappingId);

    void deleteByMappingId(UUID mappingId);
}
